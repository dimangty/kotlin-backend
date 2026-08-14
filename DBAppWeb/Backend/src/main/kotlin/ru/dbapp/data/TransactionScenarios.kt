package ru.dbapp.data

import java.sql.Connection
import java.sql.SQLException
import java.sql.Savepoint
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** ACID, аномалии и уровни изоляции используют одни и те же две таблицы и собраны рядом. */
internal object TransactionScenarios {
    /** Идентификаторы полностью совпадают с DemoCatalog. */
    val scenarios: Map<String, DemoScenario> = mapOf(
        "acid-transfer" to { acidTransfer() },
        "acid-error" to { acidError() },
        "acid-savepoint" to { acidSavepoint() },
        "anomaly-dirty-read" to { dirtyRead() },
        "anomaly-non-repeatable" to { nonRepeatableRead() },
        "anomaly-phantom" to { phantomRead() },
        "anomaly-lost-update" to { lostUpdate() },
        "anomaly-write-skew" to { writeSkew(Connection.TRANSACTION_READ_COMMITTED, "Read Committed") },
        "isolation-read-uncommitted" to { dirtyRead() },
        "isolation-read-committed-snapshot" to { readCommittedSnapshot() },
        "isolation-atomic-debit" to { atomicDebit() },
        "isolation-update-reevaluation" to { updateReevaluation() },
        "isolation-repeatable-snapshot" to { repeatableSnapshot() },
        "isolation-repeatable-conflict" to { repeatableConflict() },
        "isolation-repeatable-write-skew" to { writeSkew(Connection.TRANSACTION_REPEATABLE_READ, "Repeatable Read") },
        "isolation-serializable" to { serializableWriteSkew() },
        "isolation-report" to { consistentReport() },
    )

    /** Атомарный перевод проверяет обе суммы до и после COMMIT. */
    private fun ScenarioContext.acidTransfer() {
        db.resetAccounts()
        db.open("acid-transfer").use { connection ->
            connection.autoCommit = false
            try {
                val before = connection.queryRows("SELECT owner, balance FROM accounts WHERE owner IN ('Alice', 'Bob') ORDER BY owner")
                log.step("До перевода: ${before.joinToString()}")
                check(connection.executeUpdate("UPDATE accounts SET balance = balance - 100 WHERE owner = 'Alice'") == 1)
                check(connection.executeUpdate("UPDATE accounts SET balance = balance + 100 WHERE owner = 'Bob'") == 1)
                connection.commit()
                val after = connection.queryRows("SELECT owner, balance FROM accounts WHERE owner IN ('Alice', 'Bob') ORDER BY owner")
                log.result("После COMMIT: ${after.joinToString()}")
                log.result("Atomicity: обе записи зафиксированы вместе; сумма Alice + Bob осталась 2000.")
            } catch (error: Throwable) {
                connection.rollbackQuietly()
                throw error
            }
        }
    }

    /** CHECK переводит транзакцию в aborted; обычный запрос до ROLLBACK также отклоняется. */
    private fun ScenarioContext.acidError() {
        db.resetAccounts()
        db.open("acid-error").use { connection ->
            connection.autoCommit = false
            try {
                connection.executeUpdate("UPDATE accounts SET balance = balance - 5000 WHERE owner = 'Alice'")
                error("Ожидалось нарушение CHECK balance >= 0")
            } catch (error: SQLException) {
                log.expected("CHECK остановил отрицательный баланс: SQLSTATE ${error.sqlState}.")
            }

            try {
                connection.queryString("SELECT balance FROM accounts WHERE owner = 'Alice'")
            } catch (error: SQLException) {
                log.expected("До ROLLBACK транзакция aborted: SQLSTATE ${error.sqlState}.")
            }
            connection.rollback()
            val balance = connection.queryString("SELECT balance FROM accounts WHERE owner = 'Alice'")
            log.result("После ROLLBACK баланс Alice снова доступен и равен $balance.")
        }
    }

    /** Savepoint отменяет только бонусный подшаг, но устойчивость наступает после внешнего COMMIT. */
    private fun ScenarioContext.acidSavepoint() {
        db.resetAccounts()
        db.open("acid-savepoint").use { connection ->
            connection.autoCommit = false
            try {
                connection.executeUpdate("UPDATE accounts SET balance = balance - 50 WHERE owner = 'Alice'")
                val savepoint: Savepoint = connection.setSavepoint("before_bonus")
                val bonusRows = connection.executeUpdate("UPDATE accounts SET balance = balance + 100 WHERE owner = 'Unknown'")
                log.step("Необязательный бонус обновил $bonusRows строк; бизнес-логика откатывает этот подшаг.")
                connection.rollback(savepoint)
                connection.executeUpdate("UPDATE accounts SET balance = balance + 50 WHERE owner = 'Bob'")
                connection.commit()
                log.result(connection.queryRows("SELECT owner, balance FROM accounts WHERE owner IN ('Alice', 'Bob') ORDER BY owner").joinToString())
            } catch (error: Throwable) {
                connection.rollbackQuietly()
                throw error
            }
        }
    }

    /** Даже запрошенный READ UNCOMMITTED видит только последнюю зафиксированную версию. */
    private fun ScenarioContext.dirtyRead() {
        db.resetAccounts()
        db.open("dirty-A").use { a ->
            db.open("dirty-B").use { b ->
                a.begin(Connection.TRANSACTION_READ_COMMITTED)
                b.begin(Connection.TRANSACTION_READ_UNCOMMITTED)
                try {
                    a.executeUpdate("UPDATE accounts SET balance = 1 WHERE owner = 'Alice'")
                    log.step("Сессия A записала balance=1, но не выполнила COMMIT.")
                    val level = b.queryString("SHOW transaction_isolation")
                    val visible = b.queryString("SELECT balance FROM accounts WHERE owner = 'Alice'")
                    log.result("Сессия B запросила $level и увидела $visible, а не незакоммиченное 1.")
                    log.result("MVCC PostgreSQL не допускает dirty read by design.")
                } finally {
                    a.rollbackQuietly()
                    b.rollbackQuietly()
                }
            }
        }
    }

    /** На Read Committed новый оператор получает новый снимок и видит чужой COMMIT. */
    private fun ScenarioContext.nonRepeatableRead() {
        db.resetAccounts()
        db.open("non-repeatable-A").use { a ->
            a.begin(Connection.TRANSACTION_READ_COMMITTED)
            try {
                val first = a.queryString("SELECT balance FROM accounts WHERE owner = 'Alice'")
                db.open("non-repeatable-B").use { b ->
                    b.executeUpdate("UPDATE accounts SET balance = 900 WHERE owner = 'Alice'")
                }
                val second = a.queryString("SELECT balance FROM accounts WHERE owner = 'Alice'")
                log.result("Сессия A в одной транзакции прочитала Alice сначала $first, затем $second.")
                log.expected("Это non-repeatable read: изменилась та же строка.")
                a.commit()
            } catch (error: Throwable) {
                a.rollbackQuietly()
                throw error
            }
        }
    }

    /** Phantom отличается тем, что меняется состав множества, а не значение уже прочитанной строки. */
    private fun ScenarioContext.phantomRead() {
        db.open("phantom-cleanup").use { it.executeUpdate("DELETE FROM orders WHERE email = 'phantom@example.com'") }
        db.open("phantom-A").use { a ->
            a.begin(Connection.TRANSACTION_READ_COMMITTED)
            try {
                val before = a.queryLong("SELECT count(*) FROM orders WHERE email = 'phantom@example.com'")
                db.open("phantom-B").use { b ->
                    b.executeUpdate(
                        """
                        INSERT INTO orders(customer_id, status, email, total)
                        VALUES (42, 'new', 'phantom@example.com', 100)
                        """,
                    )
                }
                val after = a.queryLong("SELECT count(*) FROM orders WHERE email = 'phantom@example.com'")
                log.result("Один предикат в сессии A вернул сначала $before строк, затем $after.")
                log.expected("Новая строка — phantom, появившийся после COMMIT сессии B.")
                a.commit()
            } finally {
                a.rollbackQuietly()
                db.open("phantom-cleanup").use { it.executeUpdate("DELETE FROM orders WHERE email = 'phantom@example.com'") }
            }
        }
    }

    /** Сначала воспроизводится затёртое значение, затем тот же смысл выражается атомарным SQL. */
    private fun ScenarioContext.lostUpdate() {
        db.resetAccounts()
        db.open("lost-A").use { a ->
            db.open("lost-B").use { b ->
                val readA = a.queryLong("SELECT balance::bigint FROM accounts WHERE owner = 'Alice'")
                val readB = b.queryLong("SELECT balance::bigint FROM accounts WHERE owner = 'Alice'")
                log.step("Обе сессии прочитали старое значение: A=$readA, B=$readB.")
                a.executeUpdate("UPDATE accounts SET balance = ${readA - 100} WHERE owner = 'Alice'")
                b.executeUpdate("UPDATE accounts SET balance = ${readB - 200} WHERE owner = 'Alice'")
                val lost = b.queryString("SELECT balance FROM accounts WHERE owner = 'Alice'")
                log.expected("Плохая схема закончилась балансом $lost вместо ожидаемых 700: изменение A потеряно.")
            }
        }

        db.resetAccounts()
        db.open("lost-safe-A").use { it.executeUpdate("UPDATE accounts SET balance = balance - 100 WHERE owner = 'Alice'") }
        db.open("lost-safe-B").use { it.executeUpdate("UPDATE accounts SET balance = balance - 200 WHERE owner = 'Alice'") }
        db.open("lost-result").use { connection ->
            log.result("Два атомарных UPDATE balance = balance - delta дали ${connection.queryString("SELECT balance FROM accounts WHERE owner = 'Alice'")}.")
        }
    }

    /** Две транзакции читают один snapshot и меняют разные строки, поэтому write-write конфликта нет. */
    private fun ScenarioContext.writeSkew(isolation: Int, levelName: String) {
        db.resetDoctors()
        db.open("write-skew-A").use { a ->
            db.open("write-skew-B").use { b ->
                a.begin(isolation)
                b.begin(isolation)
                try {
                    val seenA = a.queryLong("SELECT count(*) FROM doctors WHERE on_call")
                    val seenB = b.queryLong("SELECT count(*) FROM doctors WHERE on_call")
                    a.executeUpdate("UPDATE doctors SET on_call = false WHERE name = 'Иван'")
                    b.executeUpdate("UPDATE doctors SET on_call = false WHERE name = 'Мария'")
                    b.commit()
                    a.commit()
                    val remaining = db.open("write-skew-result").use { it.queryLong("SELECT count(*) FROM doctors WHERE on_call") }
                    log.step("На $levelName обе сессии увидели A=$seenA и B=$seenB дежурных.")
                    log.expected("Изменялись разные строки, обе транзакции завершились; дежурных осталось $remaining.")
                } catch (error: Throwable) {
                    a.rollbackQuietly()
                    b.rollbackQuietly()
                    throw error
                }
            }
        }
    }

    /** Снимки и значения показываются вместе: строка меняется гарантированно, текст snapshot — диагностически. */
    private fun ScenarioContext.readCommittedSnapshot() {
        db.resetAccounts()
        db.open("rc-snapshot-A").use { a ->
            a.begin(Connection.TRANSACTION_READ_COMMITTED)
            try {
                val snapshot1 = a.queryString("SELECT pg_current_snapshot()::text")
                val balance1 = a.queryString("SELECT balance FROM accounts WHERE owner = 'Alice'")
                db.open("rc-snapshot-B").use { it.executeUpdate("UPDATE accounts SET balance = 875 WHERE owner = 'Alice'") }
                val snapshot2 = a.queryString("SELECT pg_current_snapshot()::text")
                val balance2 = a.queryString("SELECT balance FROM accounts WHERE owner = 'Alice'")
                log.result("Первый оператор: snapshot=$snapshot1, balance=$balance1.")
                log.result("Второй оператор: snapshot=$snapshot2, balance=$balance2.")
                log.step("Read Committed берёт снимок на начало каждого оператора.")
                a.commit()
            } catch (error: Throwable) {
                a.rollbackQuietly()
                throw error
            }
        }
    }

    /** affected rows/RETURNING становится частью бизнес-результата, а не игнорируемой метрикой. */
    private fun ScenarioContext.atomicDebit() {
        db.resetAccounts()
        db.open("atomic-debit").use { connection ->
            val balance = connection.queryString(
                "UPDATE accounts SET balance = balance - 100 WHERE owner = 'Alice' AND balance >= 100 RETURNING balance",
            )
            val rejected = connection.executeUpdate(
                "UPDATE accounts SET balance = balance - 5000 WHERE owner = 'Alice' AND balance >= 5000",
            )
            log.result("Первое списание вернуло новый баланс $balance.")
            log.result("Недопустимое списание обновило $rejected строк: нет окна между SELECT и UPDATE.")
        }
    }

    /** Пока A держит row lock, B ждёт; после COMMIT условие B проверяется на актуальном balance=100. */
    private fun ScenarioContext.updateReevaluation() {
        db.resetAccounts()
        db.open("reevaluation-A").use { a ->
            a.begin(Connection.TRANSACTION_READ_COMMITTED)
            a.executeUpdate("UPDATE accounts SET balance = balance - 900 WHERE owner = 'Alice' AND balance >= 900")
            log.step("Сессия A списала 900 и удерживает блокировку без COMMIT.")

            val started = CountDownLatch(1)
            val future = async {
                db.open("reevaluation-B").use { b ->
                    b.begin(Connection.TRANSACTION_READ_COMMITTED)
                    started.countDown()
                    val rows = b.executeUpdate("UPDATE accounts SET balance = balance - 200 WHERE owner = 'Alice' AND balance >= 200")
                    b.commit()
                    rows
                }
            }
            check(started.await(2, TimeUnit.SECONDS)) { "Сессия B не стартовала" }
            Thread.sleep(250)
            log.step("Сессия B ждёт row lock с условием balance >= 200.")
            a.commit()
            val rows = with(this) { future.await() }
            val balance = db.open("reevaluation-result").use { it.queryString("SELECT balance FROM accounts WHERE owner = 'Alice'") }
            log.result("После COMMIT A PostgreSQL заново проверил WHERE: B обновила $rows строк, итоговый balance=$balance.")
        }
    }

    /** Snapshot Repeatable Read остаётся неизменным до COMMIT, хотя новая транзакция уже видит чужую запись. */
    private fun ScenarioContext.repeatableSnapshot() {
        db.resetAccounts()
        db.open("rr-snapshot-A").use { a ->
            a.begin(Connection.TRANSACTION_REPEATABLE_READ)
            try {
                val first = a.queryString("SELECT balance FROM accounts WHERE owner = 'Alice'")
                db.open("rr-snapshot-B").use { it.executeUpdate("UPDATE accounts SET balance = 800 WHERE owner = 'Alice'") }
                val second = a.queryString("SELECT balance FROM accounts WHERE owner = 'Alice'")
                val fresh = db.open("rr-snapshot-C").use { it.queryString("SELECT balance FROM accounts WHERE owner = 'Alice'") }
                log.result("Сессия A повторно видит $first -> $second, а новая сессия уже видит $fresh.")
                log.step("PostgreSQL Repeatable Read устраняет и non-repeatable read, и phantom read.")
                a.commit()
            } catch (error: Throwable) {
                a.rollbackQuietly()
                throw error
            }
        }
    }

    /** Изменение строки, обновлённой после старта старого snapshot, штатно отклоняется. */
    private fun ScenarioContext.repeatableConflict() {
        db.resetAccounts()
        db.open("rr-conflict-A").use { a ->
            a.begin(Connection.TRANSACTION_REPEATABLE_READ)
            a.queryString("SELECT balance FROM accounts WHERE owner = 'Alice'")
            db.open("rr-conflict-B").use { it.executeUpdate("UPDATE accounts SET balance = balance - 100 WHERE owner = 'Alice'") }
            try {
                a.executeUpdate("UPDATE accounts SET balance = balance - 50 WHERE owner = 'Alice'")
                error("Ожидалась serialization failure")
            } catch (error: SQLException) {
                log.expected("Старый snapshot не может изменить новую версию: SQLSTATE ${error.sqlState}.")
                check(error.sqlState == "40001") { "Ожидался SQLSTATE 40001, получен ${error.sqlState}" }
                a.rollback()
                log.result("Повторять нужно всю транзакцию, чтобы получить новый snapshot.")
            }
        }
    }

    /** SSI обнаруживает rw-цикл; затем проигравшая бизнес-операция повторяется с новым снимком. */
    private fun ScenarioContext.serializableWriteSkew() {
        db.resetDoctors()
        var serializationFailure: SQLException? = null

        db.open("serializable-A").use { a ->
            db.open("serializable-B").use { b ->
                a.begin(Connection.TRANSACTION_SERIALIZABLE)
                b.begin(Connection.TRANSACTION_SERIALIZABLE)
                try {
                    val seenA = a.queryLong("SELECT count(*) FROM doctors WHERE on_call")
                    val seenB = b.queryLong("SELECT count(*) FROM doctors WHERE on_call")
                    a.executeUpdate("UPDATE doctors SET on_call = false WHERE name = 'Иван'")
                    b.executeUpdate("UPDATE doctors SET on_call = false WHERE name = 'Мария'")
                    log.step("Обе SERIALIZABLE-сессии прочитали A=$seenA, B=$seenB и изменили разные строки.")

                    try {
                        b.commit()
                    } catch (error: SQLException) {
                        serializationFailure = error
                        b.rollbackQuietly()
                    }
                    try {
                        a.commit()
                    } catch (error: SQLException) {
                        serializationFailure = error
                        a.rollbackQuietly()
                    }
                } finally {
                    if (!a.autoCommit) a.rollbackQuietly()
                    if (!b.autoCommit) b.rollbackQuietly()
                }
            }
        }

        val failure = checkNotNull(serializationFailure) { "SSI не отменил опасный цикл" }
        check(failure.sqlState == "40001") { "Ожидался SQLSTATE 40001, получен ${failure.sqlState}" }
        log.expected("SSI отменил одну транзакцию: SQLSTATE ${failure.sqlState}.")

        // Повтор моделирует полную бизнес-операцию: заново читает инвариант и уже не выключает последнего врача.
        db.open("serializable-retry").use { retry ->
            retry.begin(Connection.TRANSACTION_SERIALIZABLE)
            val onCall = retry.queryLong("SELECT count(*) FROM doctors WHERE on_call")
            if (onCall > 1) retry.executeUpdate("UPDATE doctors SET on_call = false WHERE name = 'Мария' AND on_call")
            retry.commit()
            log.result("Полный retry увидел $onCall дежурного и сохранил инвариант.")
        }
        val remaining = db.open("serializable-result").use { it.queryLong("SELECT count(*) FROM doctors WHERE on_call") }
        log.result("После retry дежурных осталось $remaining.")
    }

    /** READ ONLY Repeatable Read подходит отчёту, которому нужен единый момент времени. */
    private fun ScenarioContext.consistentReport() {
        db.resetAccounts()
        db.open("report-A").use { report ->
            report.transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ
            report.isReadOnly = true
            report.autoCommit = false
            try {
                val first = report.queryString("SELECT sum(balance) FROM accounts")
                db.open("report-B").use { writer ->
                    writer.executeUpdate("UPDATE accounts SET balance = balance + 100 WHERE owner = 'Alice'")
                }
                val second = report.queryString("SELECT sum(balance) FROM accounts")
                val fresh = db.open("report-C").use { it.queryString("SELECT sum(balance) FROM accounts") }
                log.result("READ ONLY-отчёт дважды получил сумму $first -> $second; новая сессия получила $fresh.")
                log.step("Для строгой сериализуемости read-only отчёт можно открыть SERIALIZABLE READ ONLY DEFERRABLE.")
                report.commit()
            } catch (error: Throwable) {
                report.rollbackQuietly()
                throw error
            }
        }
    }
}

/** Уровень задаётся до первого SQL-запроса, как требует PostgreSQL. */
internal fun Connection.begin(isolation: Int) {
    transactionIsolation = isolation
    autoCommit = false
}
