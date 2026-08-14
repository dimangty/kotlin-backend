package ru.dbappweb.api

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import ru.dbappweb.model.ConnectionInfo
import ru.dbappweb.model.DemoApiClient
import ru.dbappweb.model.DemoReport
import ru.dbappweb.model.ParallelParticipantReport
import kotlin.random.Random

/**
 * Общая REST-реализация не знает, работает ли транспорт через браузерный Fetch API или JVM CIO.
 * Платформенная часть передаёт готовый HttpClient, а весь контракт запросов остаётся единым.
 */
internal class KtorDemoApiClient(
    private val client: HttpClient,
    baseUrl: String,
) : DemoApiClient {
    // Завершающий слеш убирается один раз, чтобы пути API никогда не содержали двойной разделитель.
    private val baseUrl = baseUrl.trimEnd('/')

    /** GET /api/status проверяет Spring Boot и настоящее JDBC-соединение с PostgreSQL. */
    override suspend fun connectionInfo(): ConnectionInfo =
        client.get("$baseUrl/api/status").body()

    /**
     * Два async-блока немедленно начинают независимые POST-запросы с общим runId.
     * Серверный барьер не откроется, пока оба запроса действительно не окажутся на бэкенде.
     */
    override suspend fun runExample(exampleId: String): DemoReport = coroutineScope {
        val runId = newParallelRunId()
        val responses = executeParallelParticipants(PARALLEL_REQUEST_COUNT) { participant ->
                client.post(
                    "$baseUrl/api/examples/$exampleId/parallel-runs/$runId/participants/$participant",
                ) {
                    parameter("participantCount", PARALLEL_REQUEST_COUNT)
                }.body<ParallelParticipantReport>()
        }.sortedBy { it.participant }

        // Строгая проверка не позволяет тихо показать логи от другого или неполного запуска.
        check(responses.size == PARALLEL_REQUEST_COUNT)
        check(responses.all { it.runId == runId && it.participantCount == PARALLEL_REQUEST_COUNT })
        check(responses.map { it.participant } == (0 until PARALLEL_REQUEST_COUNT).toList())

        val title = responses.first().title
        DemoReport(
            title = title,
            successful = responses.all { it.successful },
            lines = buildList {
                add("[CLIENT] runId=$runId: отправлено $PARALLEL_REQUEST_COUNT параллельных POST-запроса.")
                responses.forEach { response ->
                    add("")
                    add(
                        "[CLIENT] Ответ HTTP ${response.participant + 1}/${response.participantCount}; " +
                            "исполнитель ${response.executorParticipant + 1}; ${response.durationMs} мс.",
                    )
                    addAll(response.lines)
                }
            },
        )
    }

    /** Закрытие освобождает ресурсы Fetch/CIO при уничтожении Compose-клиента. */
    override fun close() {
        client.close()
    }
}

/** Отдельная функция делает конкурентный fan-out проверяемым без особенностей тестового HTTP-движка. */
internal suspend fun executeParallelParticipants(
    participantCount: Int,
    request: suspend (participant: Int) -> ParallelParticipantReport,
): List<ParallelParticipantReport> = coroutineScope {
    (0 until participantCount).map { participant ->
        async { request(participant) }
    }.awaitAll()
}

// Для каждого нажатия генерируется безопасный для URL 128-битный идентификатор без платформенных API.
private fun newParallelRunId(): String = Random.nextBytes(16).joinToString(separator = "") { byte ->
    (byte.toInt() and 0xFF).toString(16).padStart(length = 2, padChar = '0')
}

// Два участника наглядно демонстрируют одновременный fan-out и не перегружают локальный сервер.
private const val PARALLEL_REQUEST_COUNT = 2
