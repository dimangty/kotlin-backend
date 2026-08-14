package ru.dbappweb.backend.parallel

import ru.dbapp.data.DemoScenarioExecutor
import ru.dbapp.model.DemoReport
import ru.dbapp.model.ParallelParticipantReport
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Координатор проверяется без PostgreSQL: важны барьер, один исполнитель и два отдельных ответа. */
class ParallelScenarioCoordinatorTest {
    @Test
    fun `два параллельных участника запускают JDBC сценарий ровно один раз`() {
        val executions = AtomicInteger()
        val coordinator = ParallelScenarioCoordinator(
            DemoScenarioExecutor { exampleId ->
                executions.incrementAndGet()
                DemoReport(
                    title = exampleId,
                    lines = listOf("[OK] JDBC-сценарий выполнен"),
                    successful = true,
                )
            },
        )
        val pool = Executors.newFixedThreadPool(2)

        try {
            val futures = (0 until 2).map { participant ->
                pool.submit<ParallelParticipantReport> {
                    coordinator.runParticipant(
                        exampleId = "acid-transfer",
                        runId = "0123456789abcdef0123456789abcdef",
                        participant = participant,
                        participantCount = 2,
                    )
                }
            }
            val responses = futures.map { it.get(5, TimeUnit.SECONDS) }.sortedBy { it.participant }

            assertEquals(1, executions.get())
            assertEquals(listOf(0, 1), responses.map { it.participant })
            assertEquals(1, responses.map { it.executorParticipant }.distinct().size)
            assertTrue(responses.all { it.successful })
            assertEquals(
                1,
                responses.flatMap { it.lines }.count { it == "[OK] JDBC-сценарий выполнен" },
            )
            assertTrue(responses.all { response -> response.lines.any { "Барьер открыт" in it } })
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `координатор отклоняет запуск без двух участников`() {
        val coordinator = ParallelScenarioCoordinator(
            DemoScenarioExecutor { error("Сценарий не должен запускаться") },
        )

        assertFailsWith<IllegalArgumentException> {
            coordinator.runParticipant(
                exampleId = "acid-transfer",
                runId = "fedcba9876543210fedcba9876543210",
                participant = 0,
                participantCount = 1,
            )
        }
    }
}
