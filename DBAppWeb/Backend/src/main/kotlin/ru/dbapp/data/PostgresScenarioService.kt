package ru.dbapp.data

import jakarta.annotation.PreDestroy
import org.springframework.stereotype.Service
import ru.dbapp.model.ConnectionInfo
import ru.dbapp.model.DatabaseSettings
import ru.dbapp.model.DemoCatalog
import ru.dbapp.model.DemoReport
import ru.dbappweb.backend.config.DemoDatabaseProperties
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Сервис переносит desktop JDBC-сценарии за REST-границу.
 * Один общий lock защищает изменяемый учебный стенд от одновременного запуска в нескольких вкладках.
 */
@Service
class PostgresScenarioService(properties: DemoDatabaseProperties) : DemoScenarioExecutor {
    private val settings = DatabaseSettings(
        url = properties.url,
        user = properties.user,
        password = properties.password,
    )
    private val executor = newScenarioExecutor()
    private val scenarioLock = ReentrantLock()

    /** Проверка соединения идемпотентно создаёт только отдельную схему dbapp_lab. */
    fun connectionInfo(): ConnectionInfo {
        val db = JdbcDatabase(settings)
        db.initialize()
        return db.open("connection-check").use { connection ->
            ConnectionInfo(
                serverVersion = "PostgreSQL ${connection.queryString("SHOW server_version")}",
                database = connection.queryString("SELECT current_database()"),
                user = connection.queryString("SELECT current_user"),
            )
        }
    }

    /** Любая SQL-ошибка становится подробным отчётом, поэтому клиент не теряет SQLSTATE и предыдущие шаги. */
    override fun runExample(exampleId: String): DemoReport = scenarioLock.withLock {
        val example = DemoCatalog.topics
            .flatMap { it.examples }
            .firstOrNull { it.id == exampleId }
            ?: throw NoSuchElementException("Неизвестный пример: $exampleId")
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

    /** Daemon-пул завершается явно при штатной остановке Spring context. */
    @PreDestroy
    fun shutdown() {
        executor.shutdownNow()
    }
}
