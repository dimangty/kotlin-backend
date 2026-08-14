package ru.dbapp.data

import ru.dbapp.model.DatabaseSettings
import ru.dbapp.model.DemoReport
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Savepoint
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.IdentityHashMap
import java.util.Properties
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/** Исполняемый пример синхронен внутри IO-потока и сам управляет порядком двух JDBC-сессий. */
internal typealias DemoScenario = ScenarioContext.() -> Unit

/**
 * Потокобезопасный накопитель добавляет время, сессию и устойчивые маркеры к каждой строке.
 * Так параллельные JDBC-команды A/B можно восстановить в точном порядке после выполнения.
 */
internal class ScenarioLog(private val title: String) {
    private val formatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    private val entries = mutableListOf<String>()

    /** Обычный поясняющий шаг сценария. */
    @Synchronized
    fun step(message: String) = add("[STEP]", message)

    /** Важный наблюдаемый итог нескольких SQL-операций. */
    @Synchronized
    fun result(message: String) = add("[OK]", message)

    /** Ожидаемая учебная ошибка, которая подтверждает механизм. */
    @Synchronized
    fun expected(message: String) = add("[EXPECTED]", message)

    /** Открытие, настройка и закрытие JDBC-сессии показываются рядом с её SQL. */
    @Synchronized
    fun connection(sessionName: String, message: String) {
        entries += "[${now()}] [$sessionName] JDBC> $message"
    }

    /** Каждая реально отправленная PostgreSQL команда печатается отдельным блоком. */
    @Synchronized
    fun sql(sessionName: String, statement: String) {
        entries += "[${now()}] [$sessionName] SQL>"
        entries += statement.trimIndent().trim().prependIndent("    ")
    }

    /** Строки, affected rows или план EXPLAIN всегда следуют за породившим их SQL. */
    @Synchronized
    fun sqlResult(sessionName: String, lines: List<String>) {
        entries += "[${now()}] [$sessionName] РЕЗУЛЬТАТ>"
        entries += if (lines.isEmpty()) {
            "    <пустой результат>"
        } else {
            lines.joinToString("\n") { "    $it" }
        }
    }

    /** SQLException логируется до того, как учебный сценарий обработает ожидаемый SQLSTATE. */
    @Synchronized
    fun sqlError(sessionName: String, error: SQLException) {
        entries += "[${now()}] [$sessionName] ОШИБКА SQL> SQLSTATE ${error.sqlState}: ${error.message}"
    }

    /** Неожиданная ошибка завершает отчёт, но не скрывает SQLSTATE. */
    @Synchronized
    fun failure(error: Throwable) {
        val sqlState = (error as? SQLException)?.sqlState
        val suffix = sqlState?.let { " (SQLSTATE $it)" }.orEmpty()
        add("[ERROR]", "${error.message ?: error::class.simpleName}$suffix")
    }

    /** Собранный снимок является общей моделью и не содержит JDBC-типов. */
    @Synchronized
    fun report(successful: Boolean): DemoReport = DemoReport(
        title = title,
        lines = entries.toList(),
        successful = successful,
    )

    /** Общий формат помогает быстро различать пояснения и результаты в моноширинном поле. */
    private fun add(marker: String, message: String) {
        entries += "[${now()}] $marker $message"
    }

    /** Время вычисляется непосредственно перед записью для наглядного порядка двух сессий. */
    private fun now(): String = LocalTime.now().format(formatter)
}

/** Связывает proxy-объект Connection с логом и понятным учебным именем сессии. */
private data class SqlTrace(val log: ScenarioLog, val sessionName: String)

/**
 * Identity-map не вызывает equals/hashCode proxy-соединения и безопасна для параллельных JDBC-сессий.
 * Запись удаляется при close, поэтому завершённые соединения не остаются в памяти.
 */
private object SqlTraceRegistry {
    private val traces = IdentityHashMap<Connection, SqlTrace>()

    @Synchronized
    fun register(connection: Connection, trace: SqlTrace) {
        traces[connection] = trace
    }

    @Synchronized
    fun unregister(connection: Connection) {
        traces.remove(connection)
    }

    @Synchronized
    fun find(connection: Connection): SqlTrace? = traces[connection]
}

/**
 * Фабрика JDBC-соединений всегда задаёт search_path только на учебную схему.
 * При наличии [scenarioLog] учебные команды, результаты и транзакционные операции трассируются автоматически.
 * Техническая установка search_path выполняется без записи в пользовательский лог.
 */
internal class JdbcDatabase(
    private val settings: DatabaseSettings,
    private val scenarioLog: ScenarioLog? = null,
) {
    /** Открывает независимую сессию PostgreSQL с понятным application_name. */
    fun open(sessionName: String = "main"): Connection {
        require(settings.url.startsWith("jdbc:postgresql:")) {
            "DBApp поддерживает только JDBC URL PostgreSQL"
        }
        require(settings.user.isNotBlank()) { "Пользователь PostgreSQL не указан" }

        val properties = Properties().apply {
            setProperty("user", settings.user)
            setProperty("password", settings.password)
            setProperty("ApplicationName", "DBApp-$sessionName")
        }
        val rawConnection = DriverManager.getConnection(settings.url, properties)
        val connection = scenarioLog?.let { tracedConnection(rawConnection, it, sessionName) } ?: rawConnection
        return connection.also {
            // Первая сессия должна уметь создать схему до установки search_path.
            it.execute("CREATE SCHEMA IF NOT EXISTS dbapp_lab")
            it.execute("SET search_path TO dbapp_lab, public")
        }
    }

    /** Создаёт схему и небольшие таблицы из ресурса без вывода служебного DDL в учебный лог. */
    fun initialize() {
        val schema = checkNotNull(javaClass.classLoader.getResourceAsStream("db/schema.sql")) {
            "Не найден ресурс db/schema.sql"
        }.bufferedReader().use { it.readText() }

        open("initialize").use { connection ->
            connection.execute(schema)
        }
    }

    /** Возвращает счета в состояние, с которого начинаются примеры конспекта. */
    fun resetAccounts() {
        open("reset-accounts").use { connection ->
            connection.executeUpdate(
                """
                UPDATE accounts
                SET balance = CASE owner
                    WHEN 'Alice' THEN 1000
                    WHEN 'Bob' THEN 1000
                    WHEN 'Carol' THEN 500
                END,
                    version = 0
                WHERE owner IN ('Alice', 'Bob', 'Carol')
                """,
            )
        }
    }

    /** Оба врача снова дежурят перед каждым экспериментом с общим инвариантом. */
    fun resetDoctors() {
        open("reset-doctors").use { connection ->
            connection.executeUpdate("UPDATE doctors SET on_call = true")
        }
    }

    /** Очередь пересоздаётся малыми данными, чтобы SKIP LOCKED давал детерминированные id. */
    fun resetJobs() {
        open("reset-jobs").use { connection ->
            connection.execute("TRUNCATE TABLE jobs RESTART IDENTITY")
            connection.executeUpdate(
                """
                INSERT INTO jobs(status, payload)
                VALUES
                    ('queued', '{"task":"первая"}'::jsonb),
                    ('queued', '{"task":"вторая"}'::jsonb),
                    ('queued', '{"task":"третья"}'::jsonb)
                """,
            )
        }
    }

    /**
     * Большой набор из PDF создаётся только при первом индексном примере.
     * Если предыдущая генерация оборвалась, неполная таблица пересоздаётся целиком.
     */
    fun ensureOrders(progressLog: ScenarioLog) {
        open("prepare-orders").use { connection ->
            val currentCount = connection.queryLong("SELECT count(*) FROM orders")
            if (currentCount >= ORDER_COUNT) {
                progressLog.step("Набор orders уже содержит $currentCount строк; повторная генерация не нужна.")
                connection.execute("ANALYZE orders")
                return
            }

            progressLog.step(
                "Создаём ${formatNumber(ORDER_COUNT)} заказов из учебного стенда PDF. " +
                    "Первый запуск может занять несколько секунд.",
            )
            connection.execute("TRUNCATE TABLE orders RESTART IDENTITY")
            connection.executeUpdate(
                """
                INSERT INTO orders(customer_id, status, email, total, created_at, payload)
                SELECT
                    1 + floor(random() * 10000)::int,
                    (ARRAY['new', 'paid', 'cancelled'])[1 + floor(random() * 3)::int],
                    'user' || g || '@example.com',
                    round((10 + random() * 5000)::numeric, 2),
                    now() - random() * interval '365 days',
                    jsonb_build_object(
                        'channel', (ARRAY['mobile', 'web', 'partner'])[1 + floor(random() * 3)::int],
                        'country', (ARRAY['RU', 'KZ', 'BY', 'AM'])[1 + floor(random() * 4)::int]
                    )
                FROM generate_series(1, $ORDER_COUNT) AS g
                """,
            )
            connection.execute("ANALYZE orders")
            progressLog.result("Таблица orders заполнена и проанализирована.")
        }
    }

    /**
     * Proxy перехватывает commit/rollback/savepoint и настройки Connection.
     * Обычные SQL-вызовы делегируются PGConnection и трассируются helper-функциями ниже.
     */
    private fun tracedConnection(raw: Connection, log: ScenarioLog, sessionName: String): Connection {
        lateinit var proxyConnection: Connection
        val handler = java.lang.reflect.InvocationHandler { _, method, arguments ->
            val args = arguments ?: emptyArray<Any?>()
            when (method.name) {
                "commit" -> log.sql(sessionName, "COMMIT;")
                "rollback" -> {
                    val suffix = (args.firstOrNull() as? Savepoint)
                        ?.let { " TO SAVEPOINT ${it.savepointName}" }
                        .orEmpty()
                    log.sql(sessionName, "ROLLBACK$suffix;")
                }
                "setSavepoint" -> {
                    val name = args.firstOrNull()?.toString() ?: "<автоматическое имя>"
                    log.sql(sessionName, "SAVEPOINT $name;")
                }
                "releaseSavepoint" -> {
                    val savepoint = args.firstOrNull() as Savepoint
                    log.sql(sessionName, "RELEASE SAVEPOINT ${savepoint.savepointName};")
                }
                "setAutoCommit" -> log.connection(
                    sessionName,
                    "autoCommit=${args.first()}; при false транзакция начнётся с первой SQL-команды.",
                )
                "setTransactionIsolation" -> log.connection(
                    sessionName,
                    "transactionIsolation=${isolationName(args.first() as Int)}.",
                )
                "setReadOnly" -> log.connection(sessionName, "readOnly=${args.first()}.")
            }

            try {
                method.invoke(raw, *args).also {
                    when (method.name) {
                        "commit" -> log.sqlResult(sessionName, listOf("Транзакция зафиксирована."))
                        "rollback" -> log.sqlResult(sessionName, listOf("Транзакция отменена через ROLLBACK."))
                        "setSavepoint" -> log.sqlResult(sessionName, listOf("Точка сохранения создана."))
                        "releaseSavepoint" -> log.sqlResult(sessionName, listOf("Точка сохранения удалена."))
                    }
                }
            } catch (error: InvocationTargetException) {
                val target = error.targetException
                if (target is SQLException) log.sqlError(sessionName, target)
                throw target
            } finally {
                if (method.name == "close") SqlTraceRegistry.unregister(proxyConnection)
            }
        }
        proxyConnection = Proxy.newProxyInstance(
            raw.javaClass.classLoader,
            arrayOf(Connection::class.java),
            handler,
        ) as Connection
        SqlTraceRegistry.register(proxyConnection, SqlTrace(log, sessionName))
        return proxyConnection
    }

    /** Числовые константы JDBC переводятся в понятные названия уровней изоляции. */
    private fun isolationName(level: Int): String = when (level) {
        Connection.TRANSACTION_READ_UNCOMMITTED -> "READ UNCOMMITTED"
        Connection.TRANSACTION_READ_COMMITTED -> "READ COMMITTED"
        Connection.TRANSACTION_REPEATABLE_READ -> "REPEATABLE READ"
        Connection.TRANSACTION_SERIALIZABLE -> "SERIALIZABLE"
        else -> level.toString()
    }

    /** Простое форматирование без локали достаточно для русских диагностических сообщений. */
    private fun formatNumber(value: Int): String = value.toString().reversed().chunked(3).joinToString(" ").reversed()

    private companion object {
        const val ORDER_COUNT = 200_000
    }
}

/** Контекст предоставляет сценарию БД, лог и daemon-пул для реально ожидающих запросов. */
internal class ScenarioContext(
    val db: JdbcDatabase,
    val log: ScenarioLog,
    private val executor: ExecutorService,
) {
    /** Запускает оператор второй сессии параллельно, не блокируя управление сценарием. */
    fun <T> async(block: () -> T): Future<T> = executor.submit(Callable(block))

    /** Ограниченное ожидание не позволяет ошибке лаборатории навсегда повесить приложение. */
    fun <T> Future<T>.await(seconds: Long = 10): T = get(seconds, TimeUnit.SECONDS)
}

/** Daemon-потоки завершаются вместе с окном и не удерживают процесс после закрытия приложения. */
internal fun newScenarioExecutor(): ExecutorService = Executors.newFixedThreadPool(4) { runnable ->
    Thread(runnable, "dbapp-scenario").apply { isDaemon = true }
}

/** Выполняет произвольный SQL и протоколирует каждый ResultSet или update count. */
internal fun Connection.execute(sql: String) {
    val normalized = sql.trimIndent().trim()
    val shouldTrace = shouldTraceSql(normalized)
    if (shouldTrace) traceSql(normalized)
    try {
        createStatement().use { statement ->
            var hasResultSet = statement.execute(normalized)
            var resultSeen = false
            while (true) {
                if (hasResultSet) {
                    if (shouldTrace) statement.resultSet.use { result -> traceResult(result.toDisplayRows()) }
                    resultSeen = true
                } else {
                    val updateCount = statement.updateCount
                    if (updateCount == -1) break
                    if (shouldTrace) traceResult(listOf("Изменено строк: $updateCount"))
                    resultSeen = true
                }
                hasResultSet = statement.moreResults
            }
            if (shouldTrace && !resultSeen) {
                traceResult(listOf("Команда выполнена успешно; набор строк не возвращён."))
            }
        }
    } catch (error: SQLException) {
        if (shouldTrace) traceError(error)
        throw error
    }
}

/**
 * Служебное создание схем и таблиц выполняется, но не засоряет учебный лог.
 * CREATE INDEX остаётся видимым, потому что является предметом индексных экспериментов.
 */
internal fun shouldTraceSql(statement: String): Boolean =
    !HIDDEN_SETUP_DDL.containsMatchIn(statement) && !HIDDEN_SEARCH_PATH.containsMatchIn(statement)

/** Учитываются IF NOT EXISTS, TEMP/TEMPORARY и многострочные schema.sql-ресурсы. */
private val HIDDEN_SETUP_DDL = Regex(
    pattern = """\bCREATE\s+(?:(?:GLOBAL|LOCAL)\s+)?(?:(?:TEMP|TEMPORARY|UNLOGGED)\s+)?(?:SCHEMA|TABLE)\b""",
    option = RegexOption.IGNORE_CASE,
)

/** Настройка рабочей схемы обязательна для безопасности стенда, но не относится к учебному примеру. */
private val HIDDEN_SEARCH_PATH = Regex(
    pattern = """\bSET\s+(?:(?:LOCAL|SESSION)\s+)?search_path\b""",
    option = RegexOption.IGNORE_CASE,
)

/** Выполняет DML и показывает точное количество affected rows. */
internal fun Connection.executeUpdate(sql: String): Int {
    val normalized = sql.trimIndent().trim()
    traceSql(normalized)
    return try {
        createStatement().use { statement ->
            statement.executeUpdate(normalized).also { count ->
                traceResult(listOf("Изменено строк: $count"))
            }
        }
    } catch (error: SQLException) {
        traceError(error)
        throw error
    }
}

/** Читает scalar-строку и явно логирует значение или пустой результат. */
internal fun Connection.queryString(sql: String): String {
    val normalized = sql.trimIndent().trim()
    traceSql(normalized)
    return try {
        createStatement().use { statement ->
            statement.executeQuery(normalized).use { result ->
                if (!result.next()) {
                    traceResult(emptyList())
                    error("Запрос не вернул строк: $sql")
                }
                result.getString(1).also { value -> traceResult(listOf("Значение: $value")) }
            }
        }
    } catch (error: SQLException) {
        traceError(error)
        throw error
    }
}

/** Числовая версия scalar-запроса нужна счётчикам и affected-result проверкам. */
internal fun Connection.queryLong(sql: String): Long {
    val normalized = sql.trimIndent().trim()
    traceSql(normalized)
    return try {
        createStatement().use { statement ->
            statement.executeQuery(normalized).use { result ->
                if (!result.next()) {
                    traceResult(emptyList())
                    error("Запрос не вернул строк: $sql")
                }
                result.getLong(1).also { value -> traceResult(listOf("Значение: $value")) }
            }
        }
    } catch (error: SQLException) {
        traceError(error)
        throw error
    }
}

/** Boolean-запрос используется advisory lock и диагностическими предикатами. */
internal fun Connection.queryBoolean(sql: String): Boolean {
    val normalized = sql.trimIndent().trim()
    traceSql(normalized)
    return try {
        createStatement().use { statement ->
            statement.executeQuery(normalized).use { result ->
                if (!result.next()) {
                    traceResult(emptyList())
                    error("Запрос не вернул строк: $sql")
                }
                result.getBoolean(1).also { value -> traceResult(listOf("Значение: $value")) }
            }
        }
    } catch (error: SQLException) {
        traceError(error)
        throw error
    }
}

/** Универсальный вывод строк добавляет имена колонок и количество строк. */
internal fun Connection.queryRows(sql: String, maxRows: Int = 20): List<String> {
    val normalized = sql.trimIndent().trim()
    traceSql(normalized)
    return try {
        createStatement().use { statement ->
            statement.maxRows = maxRows
            statement.executeQuery(normalized).use { result ->
                result.toDisplayRows().also { rows ->
                    traceResult(listOf("Получено строк: ${rows.size} (лимит лога: $maxRows)") + rows)
                }
            }
        }
    } catch (error: SQLException) {
        traceError(error)
        throw error
    }
}

/** EXPLAIN возвращает и одновременно полностью логирует древовидный текст плана. */
internal fun Connection.explain(sql: String): List<String> {
    val normalized = "EXPLAIN (ANALYZE, BUFFERS) ${sql.trimIndent().trim()}"
    traceSql(normalized)
    return try {
        createStatement().use { statement ->
            statement.executeQuery(normalized).use { result ->
                buildList {
                    while (result.next()) add(result.getString(1))
                }.also { plan -> traceResult(plan) }
            }
        }
    } catch (error: SQLException) {
        traceError(error)
        throw error
    }
}

/** ResultSet форматируется без знания конкретной модели таблицы. */
private fun ResultSet.toDisplayRows(): List<String> {
    val columns = metaData.columnCount
    return buildList {
        while (next()) {
            add(
                (1..columns).joinToString(" | ") { index ->
                    "${metaData.getColumnLabel(index)}=${getObject(index)}"
                },
            )
        }
    }
}

/** Передаёт SQL в лог, если соединение создано для исполняемого сценария. */
private fun Connection.traceSql(sql: String) {
    SqlTraceRegistry.find(this)?.let { it.log.sql(it.sessionName, sql) }
}

/** Результат связывается с той же именованной сессией, что и команда. */
private fun Connection.traceResult(lines: List<String>) {
    SqlTraceRegistry.find(this)?.let { it.log.sqlResult(it.sessionName, lines) }
}

/** Ошибка логируется до передачи сценарию, который может считать её ожидаемой. */
private fun Connection.traceError(error: SQLException) {
    SqlTraceRegistry.find(this)?.let { it.log.sqlError(it.sessionName, error) }
}

/** После ожидаемой SQL-ошибки откат не должен скрыть исходную причину. */
internal fun Connection.rollbackQuietly() {
    runCatching { rollback() }
}
