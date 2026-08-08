package ru.dbapp.data

import java.sql.Connection
import java.sql.SQLException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Блокировки вынесены отдельно: здесь особенно важен точный порядок команд двух сессий. */
internal object LockScenarios {
    /** Десять кнопок тематического экрана имеют десять реальных JDBC-реализаций. */
    val scenarios: Map<String, DemoScenario> = mapOf(
        "lock-for-update" to { pessimisticLock() },
        "lock-nowait" to { nowait() },
        "lock-skip-locked" to { skipLocked() },
        "lock-advisory" to { advisoryLock() },
        "lock-diagnostics" to { lockDiagnostics() },
        "lock-mvcc" to { mvccVersions() },
        "lock-hot" to { hotUpdate() },
        "lock-deadlock" to { deadlock() },
        "lock-order" to { consistentLockOrder() },
        "lock-timeouts" to { lockTimeouts() },
        "lock-optimistic" to { optimisticLock() },
    )

    /** FOR UPDATE удерживает tuple lock, поэтому конкурирующий UPDATE ждёт COMMIT первой сессии. */
    private fun ScenarioContext.pessimisticLock() {
        db.resetAccounts()
        db.open("for-update-A").use { a ->
            a.begin(Connection.TRANSACTION_READ_COMMITTED)
            val locked = a.queryRows("SELECT id, balance FROM accounts WHERE owner = 'Alice' FOR UPDATE")
            log.result("Сессия A заблокировала строку: ${locked.joinToString()}.")

            val started = CountDownLatch(1)
            val bPid = AtomicInteger()
            val future = async {
                db.open("for-update-B").use { b ->
                    b.begin(Connection.TRANSACTION_READ_COMMITTED)
                    b.execute("SET LOCAL lock_timeout = '5s'")
                    bPid.set(b.queryLong("SELECT pg_backend_pid()").toInt())
                    started.countDown()
                    try {
                        val rows = b.executeUpdate("UPDATE accounts SET balance = balance - 10 WHERE owner = 'Alice'")
                        b.commit()
                        rows
                    } catch (error: Throwable) {
                        b.rollbackQuietly()
                        throw error
                    }
                }
            }
            check(started.await(2, TimeUnit.SECONDS)) { "Сессия B не стартовала" }
            Thread.sleep(250)
            val blockers = db.open("for-update-inspect").use { inspect ->
                inspect.queryString("SELECT pg_blocking_pids(${bPid.get()})::text")
            }
            log.step("Сессия B ждёт; pg_blocking_pids(${bPid.get()}) = $blockers.")
            a.executeUpdate("UPDATE accounts SET balance = balance - 100 WHERE owner = 'Alice'")
            a.commit()
            val rows = with(this) { future.await() }
            val balance = db.open("for-update-result").use { it.queryString("SELECT balance FROM accounts WHERE owner = 'Alice'") }
            log.result("После COMMIT A сессия B продолжила работу, обновила $rows строк; balance=$balance.")
        }
    }

    /** NOWAIT заменяет ожидание немедленной диагностической ошибкой lock_not_available. */
    private fun ScenarioContext.nowait() {
        db.resetAccounts()
        db.open("nowait-A").use { a ->
            db.open("nowait-B").use { b ->
                a.begin(Connection.TRANSACTION_READ_COMMITTED)
                b.begin(Connection.TRANSACTION_READ_COMMITTED)
                try {
                    a.queryRows("SELECT id FROM accounts WHERE owner = 'Alice' FOR UPDATE")
                    log.step("Сессия A удерживает FOR UPDATE на Alice.")
                    try {
                        b.queryRows("SELECT id FROM accounts WHERE owner = 'Alice' FOR UPDATE NOWAIT")
                        error("NOWAIT неожиданно получил занятую строку")
                    } catch (error: SQLException) {
                        log.expected("NOWAIT сразу вернул SQLSTATE ${error.sqlState} (${error.message?.lineSequence()?.firstOrNull()}).")
                        check(error.sqlState == "55P03") { "Ожидался SQLSTATE 55P03" }
                        b.rollback()
                    }
                    a.commit()
                } catch (error: Throwable) {
                    a.rollbackQuietly()
                    b.rollbackQuietly()
                    throw error
                }
            }
        }
    }

    /** Два воркера используют один атомарный CTE и забирают разные queued-строки. */
    private fun ScenarioContext.skipLocked() {
        db.resetJobs()
        val pickSql =
            """
            WITH picked AS (
                SELECT id
                FROM jobs
                WHERE status = 'queued'
                ORDER BY id
                FOR UPDATE SKIP LOCKED
                LIMIT 1
            )
            UPDATE jobs AS j
            SET status = 'processing'
            FROM picked
            WHERE j.id = picked.id
            RETURNING j.id
            """
        db.open("worker-A").use { a ->
            db.open("worker-B").use { b ->
                a.begin(Connection.TRANSACTION_READ_COMMITTED)
                b.begin(Connection.TRANSACTION_READ_COMMITTED)
                try {
                    val firstId = a.queryString(pickSql)
                    val secondId = b.queryString(pickSql)
                    log.step("A держит задачу $firstId без COMMIT; B не ждёт её и получает задачу $secondId.")
                    b.commit()
                    a.commit()
                    log.result("SKIP LOCKED распределил разные задания между конкурентными воркерами.")
                } catch (error: Throwable) {
                    a.rollbackQuietly()
                    b.rollbackQuietly()
                    throw error
                }
            }
        }
    }

    /** Транзакционный advisory lock автоматически освобождается на COMMIT/ROLLBACK. */
    private fun ScenarioContext.advisoryLock() {
        db.open("advisory-A").use { a ->
            db.open("advisory-B").use { b ->
                a.begin(Connection.TRANSACTION_READ_COMMITTED)
                b.begin(Connection.TRANSACTION_READ_COMMITTED)
                try {
                    a.execute("SELECT pg_advisory_xact_lock(42)")
                    val whileBusy = b.queryBoolean("SELECT pg_try_advisory_xact_lock(42)")
                    log.result("Пока A держит бизнес-ключ 42, попытка B вернула $whileBusy.")
                    a.commit()
                    val afterCommit = b.queryBoolean("SELECT pg_try_advisory_xact_lock(42)")
                    log.result("После COMMIT A та же попытка B вернула $afterCommit.")
                    b.commit()
                } catch (error: Throwable) {
                    a.rollbackQuietly()
                    b.rollbackQuietly()
                    throw error
                }
            }
        }
    }

    /** Диагностическая третья сессия видит не только PID, но и тип ожидаемой блокировки. */
    private fun ScenarioContext.lockDiagnostics() {
        db.resetAccounts()
        db.open("diagnostics-A").use { a ->
            a.begin(Connection.TRANSACTION_READ_COMMITTED)
            val aPid = a.queryLong("SELECT pg_backend_pid()").toInt()
            a.queryRows("SELECT id FROM accounts WHERE owner = 'Alice' FOR UPDATE")

            val started = CountDownLatch(1)
            val bPid = AtomicInteger()
            val future = async {
                db.open("diagnostics-B").use { b ->
                    b.begin(Connection.TRANSACTION_READ_COMMITTED)
                    b.execute("SET LOCAL lock_timeout = '5s'")
                    bPid.set(b.queryLong("SELECT pg_backend_pid()").toInt())
                    started.countDown()
                    try {
                        b.executeUpdate("UPDATE accounts SET balance = balance + 1 WHERE owner = 'Alice'")
                        b.commit()
                    } catch (error: Throwable) {
                        b.rollbackQuietly()
                        throw error
                    }
                }
            }
            check(started.await(2, TimeUnit.SECONDS)) { "Сессия B не стартовала" }
            Thread.sleep(250)

            val rows = db.open("diagnostics-inspect").use { inspect ->
                inspect.queryRows(
                    """
                    SELECT a.pid, a.application_name, l.locktype, l.mode, l.granted,
                           pg_blocking_pids(a.pid) AS blocked_by
                    FROM pg_locks AS l
                    JOIN pg_stat_activity AS a USING (pid)
                    WHERE a.pid IN ($aPid, ${bPid.get()})
                    ORDER BY a.pid, l.granted, l.locktype
                    """,
                    maxRows = 50,
                )
            }
            log.result("pg_locks/pg_stat_activity:\n${rows.joinToString("\n") { "    $it" }}")
            a.commit()
            with(this) { future.await() }
            log.step("Диагностировать нужно причину ожидания, а не просто увеличивать timeout.")
        }
    }

    /** Системные поля показываются только как лабораторная диагностика, не как прикладной API. */
    private fun ScenarioContext.mvccVersions() {
        db.resetAccounts()
        db.open("mvcc").use { connection ->
            val before = connection.queryRows(
                "SELECT ctid, xmin::text, xmax::text, id, owner, balance FROM accounts WHERE owner = 'Alice'",
            )
            connection.executeUpdate("UPDATE accounts SET balance = balance + 1 WHERE owner = 'Alice'")
            val after = connection.queryRows(
                "SELECT ctid, xmin::text, xmax::text, id, owner, balance FROM accounts WHERE owner = 'Alice'",
            )
            log.result("До UPDATE: ${before.joinToString()}")
            log.result("После UPDATE: ${after.joinToString()}")
            log.step("UPDATE создал новый tuple; обычный SELECT скрывает старую невидимую версию.")
            log.step("ctid/xmin/xmax нельзя использовать как стабильные бизнес-идентификаторы.")
        }
    }

    /** Наблюдаем фактические счётчики HOT до и после индекса на изменяемом balance. */
    private fun ScenarioContext.hotUpdate() {
        db.resetAccounts()
        db.open("hot-prepare").use { connection ->
            connection.execute("DROP INDEX IF EXISTS accounts_balance_demo_idx")
        }
        try {
            val before = readHotStats()
            db.open("hot-without-index").use { updater ->
                repeat(40) { updater.executeUpdate("UPDATE accounts SET balance = balance + 1 WHERE owner = 'Alice'") }
                updater.execute("SELECT pg_stat_force_next_flush()")
            }
            val middle = readHotStats()
            log.result("Без индекса balance: Δupdates=${middle.first - before.first}, ΔHOT=${middle.second - before.second}.")

            db.open("hot-add-index").use { connection ->
                connection.execute("CREATE INDEX accounts_balance_demo_idx ON accounts(balance)")
            }
            db.open("hot-with-index").use { updater ->
                repeat(40) { updater.executeUpdate("UPDATE accounts SET balance = balance + 1 WHERE owner = 'Alice'") }
                updater.execute("SELECT pg_stat_force_next_flush()")
            }
            val after = readHotStats()
            log.result("С индексом balance: Δupdates=${after.first - middle.first}, ΔHOT=${after.second - middle.second}.")
            log.step("Обновление индексированного столбца обязано изменить индекс и не может быть HOT.")
        } finally {
            db.open("hot-cleanup").use { it.execute("DROP INDEX IF EXISTS accounts_balance_demo_idx") }
        }
    }

    /** Обратный порядок Alice/Bob создаёт цикл ожидания; один исход обязательно содержит 40P01. */
    private fun ScenarioContext.deadlock() {
        db.resetAccounts()
        db.open("deadlock-A").use { a ->
            db.open("deadlock-B").use { b ->
                a.begin(Connection.TRANSACTION_READ_COMMITTED)
                b.begin(Connection.TRANSACTION_READ_COMMITTED)
                a.execute("SET LOCAL lock_timeout = '6s'")
                b.execute("SET LOCAL lock_timeout = '6s'")
                a.executeUpdate("UPDATE accounts SET balance = balance + 1 WHERE owner = 'Alice'")
                b.executeUpdate("UPDATE accounts SET balance = balance + 1 WHERE owner = 'Bob'")
                log.step("A держит Alice, B держит Bob.")

                val started = CountDownLatch(1)
                val aFuture = async {
                    started.countDown()
                    try {
                        a.executeUpdate("UPDATE accounts SET balance = balance + 1 WHERE owner = 'Bob'")
                        a.commit()
                        LockOutcome(committed = true, sqlState = null)
                    } catch (error: SQLException) {
                        a.rollbackQuietly()
                        LockOutcome(committed = false, sqlState = error.sqlState)
                    }
                }
                check(started.await(2, TimeUnit.SECONDS)) { "Второй UPDATE A не стартовал" }
                Thread.sleep(200)

                val bOutcome = try {
                    b.executeUpdate("UPDATE accounts SET balance = balance + 1 WHERE owner = 'Alice'")
                    b.commit()
                    LockOutcome(committed = true, sqlState = null)
                } catch (error: SQLException) {
                    b.rollbackQuietly()
                    LockOutcome(committed = false, sqlState = error.sqlState)
                }
                val aOutcome = with(this) { aFuture.await(seconds = 12) }
                val states = listOfNotNull(aOutcome.sqlState, bOutcome.sqlState)
                check("40P01" in states) { "PostgreSQL не сообщил ожидаемый deadlock: $states" }
                log.expected("Цикл обнаружен: A=$aOutcome, B=$bOutcome; одна транзакция получила SQLSTATE 40P01.")
                log.step("Приложение повторяет всю жертву, но одновременно исправляет порядок захвата.")
            }
        }
    }

    /** Обе транзакции блокируют id по возрастанию: B ждёт A, но владение никогда не образует цикл. */
    private fun ScenarioContext.consistentLockOrder() {
        db.resetAccounts()
        val sql =
            """
            SELECT id, owner
            FROM accounts
            WHERE owner IN ('Alice', 'Bob')
            ORDER BY id
            FOR UPDATE
            """
        db.open("ordered-A").use { a ->
            a.begin(Connection.TRANSACTION_READ_COMMITTED)
            val firstLocks = a.queryRows(sql)
            log.result("A получила блокировки в порядке: ${firstLocks.joinToString()}.")

            val started = CountDownLatch(1)
            val future = async {
                db.open("ordered-B").use { b ->
                    b.begin(Connection.TRANSACTION_READ_COMMITTED)
                    started.countDown()
                    try {
                        val rows = b.queryRows(sql)
                        b.commit()
                        rows
                    } catch (error: Throwable) {
                        b.rollbackQuietly()
                        throw error
                    }
                }
            }
            check(started.await(2, TimeUnit.SECONDS)) { "Сессия B не стартовала" }
            Thread.sleep(250)
            log.step("B ждёт первый id, не удерживая второй: ребро ожидания только одно.")
            a.commit()
            val secondLocks = with(this) { future.await() }
            log.result("После COMMIT A сессия B получила тот же порядок: ${secondLocks.joinToString()}.")
            log.result("Ожидание возможно, дедлок невозможен.")
        }
    }

    /** lock_timeout прерывает только ожидание блокировки и не маскируется под дедлок 40P01. */
    private fun ScenarioContext.lockTimeouts() {
        db.resetAccounts()
        db.open("timeout-A").use { a ->
            db.open("timeout-B").use { b ->
                a.begin(Connection.TRANSACTION_READ_COMMITTED)
                b.begin(Connection.TRANSACTION_READ_COMMITTED)
                try {
                    a.queryRows("SELECT id FROM accounts WHERE owner = 'Alice' FOR UPDATE")
                    b.execute("SET LOCAL lock_timeout = '300ms'")
                    b.execute("SET LOCAL statement_timeout = '2s'")
                    log.step("A удерживает Alice; B задаёт lock_timeout=300ms и statement_timeout=2s.")
                    try {
                        b.executeUpdate("UPDATE accounts SET balance = balance + 1 WHERE owner = 'Alice'")
                        error("Ожидался lock timeout")
                    } catch (error: SQLException) {
                        log.expected("Ожидание прервано SQLSTATE ${error.sqlState}: ${error.message?.lineSequence()?.firstOrNull()}.")
                        check(error.sqlState == "55P03") { "Ожидался SQLSTATE 55P03" }
                        b.rollback()
                    }
                    a.commit()
                    log.step("Таймаут ограничил ущерб, но архитектурное лечение — короткая транзакция и единый lock order.")
                } catch (error: Throwable) {
                    a.rollbackQuietly()
                    b.rollbackQuietly()
                    throw error
                }
            }
        }
    }

    /** Version в WHERE превращает тихое затирание в обнаруженный конфликт affectedRows=0. */
    private fun ScenarioContext.optimisticLock() {
        db.resetAccounts()
        db.open("optimistic-reader").use { reader ->
            val snapshot = reader.queryRows("SELECT id, balance, version FROM accounts WHERE owner = 'Alice'").single()
            val oldVersion = reader.queryLong("SELECT version FROM accounts WHERE owner = 'Alice'")
            log.step("Клиент прочитал: $snapshot.")

            db.open("optimistic-concurrent").use { concurrent ->
                concurrent.executeUpdate(
                    "UPDATE accounts SET balance = 950, version = version + 1 WHERE owner = 'Alice'",
                )
            }
            val staleRows = reader.executeUpdate(
                """
                UPDATE accounts
                SET balance = 900, version = version + 1
                WHERE owner = 'Alice' AND version = $oldVersion
                """,
            )
            log.result("Запись со старой version=$oldVersion обновила $staleRows строк.")
            log.step("Приложение перечитывает запись или сообщает пользователю о конфликте.")
        }
    }

    /** Статистика читается отдельной сессией после принудительной публикации счётчиков. */
    private fun ScenarioContext.readHotStats(): Pair<Long, Long> = db.open("hot-stats").use { connection ->
        val values = connection.queryRows(
            "SELECT n_tup_upd, n_tup_hot_upd FROM pg_stat_user_tables WHERE schemaname='dbapp_lab' AND relname='accounts'",
        )
        val updateCount = connection.queryLong(
            "SELECT n_tup_upd FROM pg_stat_user_tables WHERE schemaname='dbapp_lab' AND relname='accounts'",
        )
        val hotCount = connection.queryLong(
            "SELECT n_tup_hot_upd FROM pg_stat_user_tables WHERE schemaname='dbapp_lab' AND relname='accounts'",
        )
        check(values.isNotEmpty()) { "Нет статистики accounts" }
        updateCount to hotCount
    }

    /** Компактный итог нужен, потому что жертвой дедлока может стать любая из двух сессий. */
    private data class LockOutcome(val committed: Boolean, val sqlState: String?)
}
