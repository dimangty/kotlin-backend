package ru.dbappweb.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import ru.dbappweb.model.ParallelParticipantReport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Тест фиксирует параллельный REST-контракт, который одинаково используют Web и Desktop. */
class KtorDemoApiClientTest {
    @Test
    fun sendsTwoRequestsInParallelAndMergesTheirLogs() = runTest {
        val mutex = Mutex()
        val arrivedParticipants = mutableSetOf<Int>()
        val requests = mutableListOf<Triple<HttpMethod, String, String?>>()
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            mutex.withLock {
                requests += Triple(request.method, path, request.url.parameters["participantCount"])
            }

            if (path == "/api/status") {
                return@MockEngine jsonResponse(
                    """{"serverVersion":"PostgreSQL 18","database":"dbappweb","user":"dbapp"}""",
                )
            }

            val segments = path.trim('/').split('/')
            val runId = segments[4]
            val participant = segments.last().toInt()
            mutex.withLock {
                arrivedParticipants += participant
            }

            val participantLine = if (participant == 0) "[OK] COMMIT" else "[HTTP 2/2] Ожидание завершено"
            jsonResponse(
                """{"runId":"$runId","participant":$participant,"participantCount":2,"executorParticipant":0,"title":"Денежный перевод","lines":["$participantLine"],"successful":true,"durationMs":12}""",
            )
        }
        val httpClient = HttpClient(engine) {
            install(ContentNegotiation) { json() }
        }
        val apiClient = KtorDemoApiClient(
            client = httpClient,
            // Завершающий слеш намеренно проверяет нормализацию базового URL.
            baseUrl = "http://localhost:18082/",
        )

        val info = apiClient.connectionInfo()
        // MockEngine работает на собственном dispatcher, поэтому здесь не используется виртуальный timeout runTest.
        val report = apiClient.runExample("acid-transfer")
        apiClient.close()

        assertEquals("PostgreSQL 18", info.serverVersion)
        assertTrue(report.successful)
        assertEquals("Денежный перевод", report.title)
        assertTrue(report.lines.any { it.startsWith("[CLIENT] runId=") })
        assertTrue(report.lines.any { it == "[OK] COMMIT" })
        assertEquals(setOf(0, 1), arrivedParticipants)

        val postRequests = requests.filter { it.first == HttpMethod.Post }
        assertEquals(2, postRequests.size)
        assertTrue(postRequests.all { it.third == "2" })
        assertEquals(
            setOf("0", "1"),
            postRequests.map { it.second.substringAfterLast('/') }.toSet(),
        )
    }

    @Test
    fun startsBothParticipantsBeforeWaitingForTheirResponses() = runTest {
        val mutex = Mutex()
        val ready = CompletableDeferred<Unit>()
        val arrived = mutableSetOf<Int>()

        val responses = withTimeout(2_000) {
            executeParallelParticipants(participantCount = 2) { participant ->
                mutex.withLock {
                    arrived += participant
                    // Первый участник продолжит работу только после фактического старта второго.
                    if (arrived.size == 2 && !ready.isCompleted) ready.complete(Unit)
                }
                ready.await()
                ParallelParticipantReport(
                    runId = "0123456789abcdef0123456789abcdef",
                    participant = participant,
                    participantCount = 2,
                    executorParticipant = 0,
                    title = "Тест",
                    lines = listOf("Участник $participant"),
                    successful = true,
                    durationMs = 1,
                )
            }
        }

        assertEquals(setOf(0, 1), arrived)
        assertEquals(listOf(0, 1), responses.map { it.participant }.sorted())
    }

    /** Все ответы MockEngine имеют настоящий JSON Content-Type для проверки ContentNegotiation. */
    private fun MockRequestHandleScope.jsonResponse(content: String) = respond(
        content = content,
        status = HttpStatusCode.OK,
        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
    )
}
