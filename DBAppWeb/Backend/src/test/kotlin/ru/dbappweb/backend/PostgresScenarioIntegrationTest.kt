package ru.dbappweb.backend

import org.junit.jupiter.api.Assumptions.assumeTrue
import ru.dbapp.data.PostgresScenarioService
import ru.dbapp.model.DemoCatalog
import ru.dbapp.model.ParallelParticipantReport
import ru.dbappweb.backend.config.DemoDatabaseProperties
import ru.dbappweb.backend.parallel.ParallelScenarioCoordinator
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Полный прогон всех кнопок включается явно и может использовать Homebrew PostgreSQL 18. */
class PostgresScenarioIntegrationTest {
    @Test
    fun `все 44 сценария выполняются на настоящем PostgreSQL`() {
        assumeTrue(
            System.getProperty("dbappweb.integration") == "true",
            "Интеграционный тест запускается с -Ddbappweb.integration=true",
        )
        val service = PostgresScenarioService(
            DemoDatabaseProperties(
                url = System.getProperty("dbappweb.url", "jdbc:postgresql://localhost:5432/postgres"),
                user = System.getProperty("dbappweb.user", System.getProperty("user.name")),
                password = System.getProperty("dbappweb.password", ""),
            ),
        )
        val coordinator = ParallelScenarioCoordinator(service)
        val clients = Executors.newFixedThreadPool(2)

        try {
            assertTrue(service.connectionInfo().serverVersion.startsWith("PostgreSQL 18"))
            DemoCatalog.topics.flatMap { it.examples }.forEachIndexed { index, example ->
                val runId = index.toString(radix = 16).padStart(length = 32, padChar = '0')
                val responses = (0 until 2).map { participant ->
                    clients.submit<ParallelParticipantReport> {
                        coordinator.runParticipant(
                            exampleId = example.id,
                            runId = runId,
                            participant = participant,
                            participantCount = 2,
                        )
                    }
                }.map { it.get(4, TimeUnit.MINUTES) }

                assertTrue(
                    responses.all { it.successful },
                    "${example.id}: ${responses.flatMap { it.lines }.takeLast(8).joinToString("\n")}",
                )
                assertEquals(1, responses.map { it.executorParticipant }.distinct().size)
            }
        } finally {
            clients.shutdownNow()
            service.shutdown()
        }
    }
}
