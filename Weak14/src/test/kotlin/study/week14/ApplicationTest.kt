// Проверяет, что контекст приложения успешно запускается.
// Тест относится к учебному модулю недели 14 и фиксирует ожидаемое поведение кода.
package study.week14

import org.junit.jupiter.api.TestInstance

import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlin.test.Test
import kotlin.test.assertEquals

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class ApplicationTest {
    // Проверяет работу health-маршрута без аннотаций контроллера.
    @Test fun `health works without annotations`() = testApplication {
        application { module() }
        assertEquals(HttpStatusCode.OK, client.get("/health").status)
    }

    // Проверяет клиентскую ошибку для пустого echo-сообщения.
    @Test fun `blank echo is a client error`() = testApplication {
        application { module() }
        val response = client.post("/echo") {
            contentType(ContentType.Application.Json)
            setBody("""{"message":" "}""")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    // Проверяет отклонение платежа без bearer-токена.
    @Test fun `payment endpoint rejects missing bearer token`() = testApplication {
        application { module() }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/payments/42").status)
    }

    // Проверяет отклонение платежа с неверным bearer-токеном.
    @Test fun `payment endpoint rejects invalid bearer token`() = testApplication {
        application { module() }
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.get("/payments/42") { bearerAuth("wrong-token") }.status,
        )
    }

    // Проверяет приём платежа с действительным bearer-токеном.
    @Test fun `payment endpoint accepts valid bearer token`() = testApplication {
        application { module() }
        assertEquals(
            HttpStatusCode.OK,
            client.get("/payments/42") { bearerAuth("study-token") }.status,
        )
    }
}
