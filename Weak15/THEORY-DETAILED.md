# Неделя 15 — подробная теория

Ktor + PostgreSQL: интеграция, транзакции и тесты.

> Задача недели — доказать себе, что корректность работы с данными живёт не во фреймворке. Тот же перевод, те же блокировки, те же инварианты, те же тесты — на другом стеке.

---

## 1. Выбор слоя доступа к данным

### 1.1 Три варианта

| | Прямой JDBC | Exposed DSL | Exposed DAO |
|---|---|---|---|
| Контроль над SQL | полный | высокий | низкий |
| Типобезопасность | нет | да | да |
| Маппинг | руками | частично | автоматически |
| Скрытые запросы | нет | нет | **есть** (lazy) |
| Подходит для | ledger, переводы, отчёты | CRUD с проверяемым SQL | простой CRUD |

Правило недели 8 не меняется: **ни один критичный запрос не должен быть неожиданностью**. Для переводов, ledger и keyset-пагинации берите прямой SQL или Exposed DSL, план которого вы видели.

### 1.2 Прямой JDBC

```kotlin
class AccountRepository {

    fun findForUpdate(conn: Connection, ids: List<Long>): List<Account> =
        conn.prepareStatement(
            """
            SELECT id, user_id, balance_minor, currency, status, version
            FROM accounts
            WHERE id = ANY(?)
            ORDER BY id                 -- единый порядок блокировки (неделя 7)
            FOR NO KEY UPDATE
            """
        ).use { st ->
            st.setArray(1, conn.createArrayOf("bigint", ids.sorted().toTypedArray()))
            st.executeQuery().use { rs -> generateSequence { if (rs.next()) rs.toAccount() else null }.toList() }
        }

    fun debit(conn: Connection, id: Long, amount: Long): Int =
        conn.prepareStatement(
            "UPDATE accounts SET balance_minor = balance_minor - ? WHERE id = ? AND balance_minor >= ?"
        ).use { st ->
            st.setLong(1, amount); st.setLong(2, id); st.setLong(3, amount)
            st.executeUpdate()
        }
}
```

Обязательные привычки: `use { }` для закрытия ресурсов, только параметризованные запросы, явное перечисление колонок, `ORDER BY id` перед блокировкой, проверка возвращаемого числа затронутых строк.

---

## 2. Пул соединений

```kotlin
fun hikari(config: ApplicationConfig): HikariDataSource = HikariDataSource(HikariConfig().apply {
    jdbcUrl = config.property("db.url").getString()
    username = config.property("db.user").getString()
    password = config.property("db.password").getString()
    maximumPoolSize = config.property("db.poolSize").getString().toInt()   // ~10
    connectionTimeout = 3_000
    maxLifetime = 20 * 60_000
    isAutoCommit = false                     // транзакциями управляем сами
    addDataSourceProperty("ApplicationName", "fintech-ktor")   // видно в pg_stat_activity
})
```

Правила из недели 8 остаются: пул небольшой (каждое соединение — процесс PostgreSQL), `connection-timeout` короткий (быстрый отказ вместо бесконечного ожидания), `maxLifetime` меньше таймаутов на стороне БД/прокси.

Закрытие при остановке:

```kotlin
monitor.subscribe(ApplicationStopping) { dataSource.close() }
```

---

## 3. Транзакции без прокси

### 3.1 Явная обёртка

```kotlin
class TransactionRunner(
    private val ds: DataSource,
    private val dispatcher: CoroutineDispatcher,   // пул, соразмерный maximumPoolSize
) {
    suspend fun <T> inTransaction(
        isolation: Int = Connection.TRANSACTION_READ_COMMITTED,
        readOnly: Boolean = false,
        block: (Connection) -> T,
    ): T = withContext(dispatcher) {
        ds.connection.use { conn ->
            conn.autoCommit = false
            conn.transactionIsolation = isolation
            conn.isReadOnly = readOnly
            try {
                val result = block(conn)
                conn.commit()
                result
            } catch (e: Throwable) {
                runCatching { conn.rollback() }
                throw e
            }
        }
    }
}
```

Сравнение со Spring:

| | Spring `@Transactional` | Ktor `inTransaction { }` |
|---|---|---|
| Границы | неявные (прокси) | буквальные (скобки блока) |
| Self-invocation | ломает транзакцию | проблемы нет |
| `propagation` | 7 режимов | вложенность делаете сами |
| Забыть открыть | легко | видно в коде |
| Читаемость | требует знания механики | прямая |

Это главный аргумент недели в пользу явности: одна из самых частых ошибок Spring-проекта здесь невозможна по конструкции.

### 3.2 Блокирующий JDBC и корутины

JDBC блокирует поток. В Ktor, где обработчик исполняется на неблокирующем движке, это особенно важно:

- **весь** транзакционный блок уходит на отдельный dispatcher;
- смена потока **внутри** транзакции недопустима — соединение привязано к блоку;
- размер dispatcher-пула соотносится с `maximumPoolSize`: 64 потока `Dispatchers.IO` при пуле в 10 просто создадут очередь. Честнее — собственный `newFixedThreadPoolContext(10, "db")`.

```kotlin
private val dbDispatcher = Executors.newFixedThreadPool(10).asCoroutineDispatcher()
```

### 3.3 Что запрещено внутри блока

То же, что на неделях 6, 8 и 11: сетевые вызовы, `delay`/`sleep`, тяжёлые вычисления, неоткатываемые побочные эффекты. Правильная схема для внешних операций — TX1 (намерение) → сеть → TX2 (результат).

### 3.4 Retry

```kotlin
private val RETRYABLE = setOf("40001", "40P01")

suspend fun <T> retrying(maxAttempts: Int = 4, block: suspend () -> T): T {
    var attempt = 0
    while (true) {
        try {
            return block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: SQLException) {
            attempt++
            if (e.sqlState !in RETRYABLE || attempt >= maxAttempts) throw e
            delay((50L shl attempt) + Random.nextLong(50))     // backoff + jitter
        }
    }
}

// использование: retry СНАРУЖИ транзакции
retrying { txRunner.inTransaction(Connection.TRANSACTION_SERIALIZABLE) { conn -> transfer(conn, cmd) } }
```

Порядок слоёв тот же, что в Spring: retry снаружи, транзакция внутри, вся бизнес-логика повторяется целиком. И то же требование: повторяемая операция обязана быть идемпотентной.

---

## 4. Flyway и миграции

```kotlin
fun Application.migrate(ds: DataSource) {
    Flyway.configure()
        .dataSource(ds)
        .locations("classpath:db/migration")
        .load()
        .migrate()
}
```

Запуск явный, в `module()`, до создания репозиториев. Правила без изменений:

- только backward-compatible миграции (expand-contract, неделя 13);
- `SET lock_timeout = '3s'` в каждой миграции;
- `CREATE INDEX CONCURRENTLY` — отдельной нетранзакционной миграцией;
- проверка применения на пустой базе в CI.

Для нескольких инстансов Flyway берёт блокировку на таблице истории; при желании миграции выносятся в отдельный шаг пайплайна.

---

## 5. Error mapping

### 5.1 Из SQLSTATE в домен

```kotlin
fun <T> mappingSqlErrors(block: () -> T): T = try {
    block()
} catch (e: SQLException) {
    throw when (e.sqlState) {
        "23505" -> DuplicateKey(constraintName(e), e)
        "23503" -> ForeignKeyViolation(e)
        "23514" -> CheckViolation(e)
        "40001", "40P01" -> TransientConflict(e)      // будет повторено
        "55P03" -> LockNotAvailable(e)
        else -> e
    }
}
```

### 5.2 Из домена в HTTP

```kotlin
install(StatusPages) {
    exception<DuplicateKey> { call, e ->
        call.respond(HttpStatusCode.Conflict, ApiError("CONFLICT", "resource already exists", requestId = call.callId))
    }
    exception<CheckViolation> { call, _ ->
        call.respond(HttpStatusCode.UnprocessableEntity, ApiError("INVARIANT_VIOLATED", ..., requestId = call.callId))
    }
    exception<InsufficientFunds> { call, _ ->
        call.respond(HttpStatusCode.UnprocessableEntity, ApiError("INSUFFICIENT_FUNDS", ..., requestId = call.callId))
    }
    exception<Throwable> { call, cause ->
        call.application.log.error("unhandled", cause)
        call.respond(HttpStatusCode.InternalServerError, ApiError("INTERNAL", "internal error", requestId = call.callId))
    }
}
```

Требование: **формат ошибки совпадает со Spring-версией**. Иначе клиент увидит два разных API, и сравнение стека перестанет быть честным. И, как всегда, наружу не уходит текст исключения БД.

---

## 6. JWT principal и ownership

```kotlin
get("/{accountId}/ledger") {
    val userId = call.principal<UserPrincipal>()!!.userId
    val accountId = call.parameters["accountId"]!!.toLong()
    val cursor = call.request.queryParameters["cursor"]?.let(Cursor::decode)

    val page = txRunner.inTransaction(readOnly = true) { conn ->
        ledgerRepository.page(conn, accountId, userId, cursor, limit = 20)
    }
    call.respond(page.toResponse())
}
```

Ownership встроен в SQL:

```sql
SELECT le.id, le.created_at, le.amount_minor
FROM ledger_entries le
JOIN accounts a ON a.id = le.account_id
WHERE le.account_id = ? AND a.user_id = ?
  AND (le.created_at, le.id) < (?, ?)
ORDER BY le.created_at DESC, le.id DESC
LIMIT ?;
```

Keyset-пагинация (неделя 5) и ownership-проверка (неделя 9) — на месте, фреймворк ни при чём.

---

## 7. Перевод целиком

```kotlin
suspend fun transfer(cmd: TransferCommand, idempotencyKey: String): TransferResult =
    retrying {
        txRunner.inTransaction { conn ->
            // 1. Идемпотентность: INSERT ... ON CONFLICT DO NOTHING
            val claimed = idempotencyRepo.claim(conn, idempotencyKey, cmd.userId, cmd.hash())
            if (!claimed) return@inTransaction idempotencyRepo.existingResult(conn, idempotencyKey, cmd.hash())

            // 2. Блокировка счетов в едином порядке id
            val accounts = accountRepo.findForUpdate(conn, listOf(cmd.from, cmd.to))
            require(accounts.size == 2) { "account not found" }

            // 3. Проверки: владение, валюта, статус, достаточность средств

            // 4. Проводки (immutable) и перевод
            val transferId = transferRepo.insert(conn, cmd)
            ledgerRepo.insertPair(conn, transferId, cmd)

            // 5. Projection с условием
            if (accountRepo.debit(conn, cmd.from, cmd.amount) == 0) throw InsufficientFunds(cmd.from)
            accountRepo.credit(conn, cmd.to, cmd.amount)

            // 6. Зафиксировать результат идемпотентности
            val result = TransferResult(transferId, ...)
            idempotencyRepo.complete(conn, idempotencyKey, result)
            result
        }
    }
```

Каждый пункт — прямая цитата недели 7. Изменился только синтаксис обрамления.

---

## 8. Тесты

### 8.1 Testcontainers + testApplication

```kotlin
class TransferIntegrationTest {

    companion object {
        val postgres = PostgreSQLContainer("postgres:17-alpine").apply { start() }
    }

    private fun ApplicationTestBuilder.setup() {
        environment {
            config = MapApplicationConfig(
                "db.url" to postgres.jdbcUrl,
                "db.user" to postgres.username,
                "db.password" to postgres.password,
                "db.poolSize" to "10",
            )
        }
        application { module() }
    }

    @Test
    fun `transfer is idempotent`() = testApplication {
        setup()
        val key = UUID.randomUUID().toString()
        repeat(2) {
            val response = client.post("/api/transfers") {
                header("Idempotency-Key", key)
                bearerAuth(token)
                contentType(ContentType.Application.Json)
                setBody(TransferRequest(from, to, 100, "USD"))
            }
            assertEquals(HttpStatusCode.Created, response.status)
        }
        assertEquals(1, countTransfers(from, to))
    }
}
```

### 8.2 Конкурентный тест

```kotlin
@Test
fun `50 concurrent transfers preserve total balance`() = testApplication {
    setup()
    val before = totalMoney()

    coroutineScope {
        val gate = CompletableDeferred<Unit>()
        val jobs = List(50) {
            async(Dispatchers.IO) {
                gate.await()
                runCatching { transferService.transfer(cmd, UUID.randomUUID().toString()) }
            }
        }
        gate.complete(Unit)                 // одновременный старт
        jobs.awaitAll()
    }

    assertEquals(before, totalMoney())
    assertTrue(balanceOf(from) >= 0)
    assertEquals(balanceOf(from), ledgerSum(from))
}
```

Те же инварианты, что на неделе 10: сохранение суммы денег, неотрицательность, согласованность projection и ledger, единственность операции на ключ.

### 8.3 Дедлок и retry

Повторить сценарии недели 7: два перевода A→B и B→A одновременно; убедиться, что при едином порядке блокировки дедлока нет, а при нарушенном — есть и он корректно повторяется. Тест на `40001` при Serializable: retry сработал, результат корректен ровно один раз.

---

## 9. Сравнительная таблица Spring / Ktor

Заполняется измерениями, не впечатлениями:

| Критерий | Spring Boot | Ktor |
|---|---|---|
| Время старта приложения | | |
| Строк кода на тот же endpoint | | |
| Прозрачность границ транзакции | неявные (прокси) | явные (блок) |
| SQL transparency | зависит от JPA | полная |
| Прозрачность DI | контейнер | конструкторы |
| Модель конкурентности | поток на запрос | корутины |
| Готовые интеграции | много | меньше |
| Время на диагностику ошибки X | | |
| Порог входа для команды | | |

Вывод формулируется как рекомендация для конкретной команды и задачи, а не как «этот лучше».

---

## 10. Типичные ошибки недели

1. Блокирующий JDBC без `withContext` — занят поток движка.
2. Транзакционный блок, внутри которого происходит смена потока.
3. Сетевой вызов внутри `inTransaction`.
4. Retry внутри транзакционного блока вместо снаружи.
5. Потерянные constraints: схема перенесена «руками» и без части ограничений.
6. Разный формат ошибок в Spring- и Ktor-версиях.
7. `dbDispatcher` заметно больше, чем `maximumPoolSize`.
8. Забытое закрытие пула при остановке приложения.
9. Блокировка счетов без `ORDER BY id`.
10. Тесты перенесены только позитивные.

---

## 11. Критерий готовности

- Одинаковые инварианты проходят в обеих реализациях.
- Constraints и границы транзакций не потерялись при переносе — проверено ревью и тестами.
- Можете аргументировать выбор Spring или Ktor для конкретной команды, опираясь на измерения.
- Дедлок/serialization retry тесты повторены и проходят.

## 12. Официальные материалы

- Ktor Server Documentation — Connection pooling and caching, Testing, Configuration.
- JetBrains Exposed — DSL, Transactions.
- HikariCP — Configuration, About Pool Sizing.
- Flyway — Migrations, Concurrency.
- Testcontainers for Java — PostgreSQL Module.
- PostgreSQL: Chapter 13 — Concurrency Control; Appendix A — Error Codes.
