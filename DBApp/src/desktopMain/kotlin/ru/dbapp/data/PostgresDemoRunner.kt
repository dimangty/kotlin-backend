package ru.dbapp.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.dbapp.model.ConnectionInfo
import ru.dbapp.model.DatabaseSettings
import ru.dbapp.model.DemoCatalog
import ru.dbapp.model.DemoReport
import ru.dbapp.model.DemoRunner

/**
 * JVM-реализация связывает идентификатор Compose-кнопки с исполняемым JDBC-сценарием.
 * Соединения не кэшируются: каждый пример явно показывает границы независимых сессий.
 */
class PostgresDemoRunner : DemoRunner {
    private val executor = newScenarioExecutor()

    /** Проверка соединения одновременно подготавливает идемпотентную схему dbapp_lab. */
    override suspend fun connect(settings: DatabaseSettings): ConnectionInfo = withContext(Dispatchers.IO) {
        val db = JdbcDatabase(settings)
        db.initialize()
        db.open("connection-check").use { connection ->
            ConnectionInfo(
                serverVersion = "PostgreSQL ${connection.queryString("SHOW server_version")}",
                database = connection.queryString("SELECT current_database()"),
                user = connection.queryString("SELECT current_user"),
            )
        }
    }

    /** Любая неожиданная ошибка превращается в отчёт и включает SQLSTATE, а UI продолжает работать. */
    override suspend fun runExample(exampleId: String, settings: DatabaseSettings): DemoReport =
        withContext(Dispatchers.IO) {
            val example = DemoCatalog.topics
                .flatMap { it.examples }
                .firstOrNull { it.id == exampleId }
                ?: error("Неизвестный пример: $exampleId")
            val scenario = ScenarioRegistry.scenarios[exampleId]
                ?: error("Для примера $exampleId не зарегистрирован JDBC-сценарий")
            val log = ScenarioLog(example.title)
            val db = JdbcDatabase(settings, log)

            try {
                db.initialize()
                log.step("Используется отдельная схема dbapp_lab; пользовательские таблицы не затрагиваются.")
                scenario(ScenarioContext(db = db, log = log, executor = executor))
                log.report(successful = true)
            } catch (error: Throwable) {
                log.failure(error)
                log.report(successful = false)
            }
        }

    /** Набор нужен тесту, который не даёт добавить в UI неработающую кнопку. */
    companion object {
        val supportedExampleIds: Set<String>
            get() = ScenarioRegistry.scenarios.keys
    }
}

/** Реестр собирается из тематических файлов, чтобы один класс не превратился в монолит. */
private object ScenarioRegistry {
    val scenarios: Map<String, DemoScenario> = buildMap {
        putAll(TransactionScenarios.scenarios)
        putAll(LockScenarios.scenarios)
        putAll(IndexScenarios.scenarios)
    }
}
