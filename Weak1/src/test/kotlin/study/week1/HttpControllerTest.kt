// Проверяет HTTP-контракт echo-эндпоинтов.
// Тест относится к учебному модулю недели 1 и фиксирует ожидаемое поведение кода.
package study.week1

import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.springframework.test.annotation.DirtiesContext

import org.junit.jupiter.api.TestInstance

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
@Execution(ExecutionMode.SAME_THREAD)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class HttpControllerTest(@Autowired private val mvc: MockMvc) {
    @Test
    // Проверяет доступность эндпоинта состояния приложения.
    fun `health is available`() {
        mvc.get("/health").andExpect { status { isOk() }; jsonPath("$.status") { value("UP") } }
    }

    @Test
    // Проверяет возврат идентификатора запроса в echo-ответе.
    fun `echo returns request id`() {
        mvc.post("/echo") {
            contentType = org.springframework.http.MediaType.APPLICATION_JSON
            header("X-Request-Id", "r-1")
            content = """{"message":"hi"}"""
        }.andExpect { status { isOk() }; jsonPath("$.requestId") { value("r-1") } }
    }
}
