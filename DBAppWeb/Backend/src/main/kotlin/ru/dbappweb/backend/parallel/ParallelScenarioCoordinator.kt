package ru.dbappweb.backend.parallel

import org.springframework.stereotype.Service
import ru.dbapp.data.DemoScenarioExecutor
import ru.dbapp.model.DemoCatalog
import ru.dbapp.model.DemoReport
import ru.dbapp.model.ParallelParticipantReport
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Координатор объединяет два одновременно пришедших HTTP-запроса по runId.
 * Барьер доказывает реальный fan-out клиента, а выбор одного исполнителя не запускает изменяющий БД сценарий дважды.
 */
@Service
class ParallelScenarioCoordinator(
    private val scenarioExecutor: DemoScenarioExecutor,
) {
    private val runs = ConcurrentHashMap<String, ParallelRunState>()

    /** Каждый MVC-поток регистрирует своего участника, ждёт пару и получает собственный журнал ответа. */
    fun runParticipant(
        exampleId: String,
        runId: String,
        participant: Int,
        participantCount: Int,
    ): ParallelParticipantReport {
        validateRequest(exampleId, runId, participant, participantCount)
        val startedAt = System.nanoTime()
        val state = runs.compute(runId) { _, current ->
            if (current == null) {
                ParallelRunState(exampleId = exampleId, participantCount = participantCount)
            } else {
                require(current.exampleId == exampleId) { "runId уже связан с другим примером" }
                require(current.participantCount == participantCount) { "Для runId изменено число участников" }
                current
            }
        } ?: error("Не удалось создать состояние параллельного запуска")

        var registered = false
        try {
            state.register(participant)
            registered = true
            check(state.ready.await(ARRIVAL_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                "Не дождались второго параллельного HTTP-запроса для runId=$runId"
            }

            // CAS гарантирует, что один и только один MVC-поток запускает изменяющий JDBC-сценарий.
            if (state.executorParticipant.compareAndSet(NO_EXECUTOR, participant)) {
                try {
                    state.result.complete(scenarioExecutor.runExample(exampleId))
                } catch (error: Throwable) {
                    state.result.completeExceptionally(error)
                }
            }

            val report = state.result.get(RESULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            val executorParticipant = state.executorParticipant.get()
            return participantReport(
                runId = runId,
                participant = participant,
                participantCount = participantCount,
                executorParticipant = executorParticipant,
                report = report,
                durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt),
            )
        } catch (error: Throwable) {
            // Ошибка одного участника немедленно будит второго и удаляет неполный запуск из памяти.
            val cause = if (error is ExecutionException) error.cause ?: error else error
            if (cause is InterruptedException) Thread.currentThread().interrupt()
            state.result.completeExceptionally(cause)
            runs.remove(runId, state)
            throw cause
        } finally {
            if (registered && state.completedParticipants.incrementAndGet() >= participantCount) {
                runs.remove(runId, state)
            }
        }
    }

    /** Валидация до создания state исключает зависшие барьеры для заведомо неверного запроса. */
    private fun validateRequest(exampleId: String, runId: String, participant: Int, participantCount: Int) {
        if (exampleId !in DemoCatalog.exampleIds) throw NoSuchElementException("Неизвестный пример: $exampleId")
        require(RUN_ID_PATTERN.matches(runId)) { "runId должен содержать 32 шестнадцатеричных символа" }
        require(participantCount == REQUIRED_PARTICIPANTS) {
            "Учебный parallel-run требует ровно $REQUIRED_PARTICIPANTS участника"
        }
        require(participant in 0 until participantCount) { "Номер участника находится вне диапазона" }
    }

    /** SQL-лог возвращается только исполнителем, поэтому клиент не печатает одинаковые строки дважды. */
    private fun participantReport(
        runId: String,
        participant: Int,
        participantCount: Int,
        executorParticipant: Int,
        report: DemoReport,
        durationMs: Long,
    ): ParallelParticipantReport {
        val prefix = "[HTTP ${participant + 1}/$participantCount]"
        val lines = buildList {
            add("$prefix Запрос зарегистрирован в parallel-run $runId.")
            add("$prefix Барьер открыт: оба HTTP-запроса одновременно находятся на бэкенде.")
            if (participant == executorParticipant) {
                add("$prefix Этот запрос выбран исполнителем JDBC-сценария.")
                addAll(report.lines)
            } else {
                add("$prefix JDBC-сценарий выполнил запрос ${executorParticipant + 1}; общий результат получен.")
            }
        }
        return ParallelParticipantReport(
            runId = runId,
            participant = participant,
            participantCount = participantCount,
            executorParticipant = executorParticipant,
            title = report.title,
            lines = lines,
            successful = report.successful,
            durationMs = durationMs,
        )
    }
}

/** Изолированное состояние живёт только от прихода первого запроса до выдачи обоих ответов. */
private class ParallelRunState(
    val exampleId: String,
    val participantCount: Int,
) {
    val ready = CountDownLatch(participantCount)
    val result = CompletableFuture<DemoReport>()
    val executorParticipant = AtomicInteger(NO_EXECUTOR)
    val completedParticipants = AtomicInteger()
    private val participants = ConcurrentHashMap.newKeySet<Int>()

    /** Повтор одного номера не может подменить отсутствующий второй параллельный запрос. */
    fun register(participant: Int) {
        require(participants.add(participant)) { "Участник $participant уже зарегистрирован" }
        ready.countDown()
    }
}

private val RUN_ID_PATTERN = Regex("[0-9a-f]{32}")
private const val REQUIRED_PARTICIPANTS = 2
private const val NO_EXECUTOR = -1
private const val ARRIVAL_TIMEOUT_SECONDS = 10L
private const val RESULT_TIMEOUT_SECONDS = 180L
