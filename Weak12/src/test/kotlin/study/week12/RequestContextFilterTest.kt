// Проверяет корреляцию запросов и очистку диагностического контекста.
// Тест относится к учебному модулю недели 12 и фиксирует ожидаемое поведение кода.
package study.week12

import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.springframework.test.annotation.DirtiesContext

import org.junit.jupiter.api.TestInstance

import io.micrometer.core.instrument.MeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
@Execution(ExecutionMode.SAME_THREAD)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class RequestContextFilterTest @Autowired constructor(
    private val mvc: MockMvc,
    private val registry: MeterRegistry,
) {
    @Test
    // Проверяет перенос идентификаторов запроса и операции в ответ.
    fun `request and operation ids propagate to response`() {
        mvc.get("/work?millis=0") {
            header("X-Request-Id", "request-42")
            header("X-Operation-Id", "operation-7")
        }.andExpect {
            status { isOk() }
            header { string("X-Request-Id", "request-42") }
            header { string("X-Operation-Id", "operation-7") }
        }
    }

    @Test
    // Проверяет замену небезопасного клиентского идентификатора.
    fun `unsafe client id is replaced`() {
        val response = mvc.get("/work?millis=0") {
            header("X-Request-Id", "unsafe id with spaces")
        }.andExpect { status { isOk() } }.andReturn().response

        assertNotEquals("unsafe id with spaces", response.getHeader("X-Request-Id"))
    }

    @Test
    // Проверяет увеличение метрики после завершения запроса.
    fun `completed request increments request metric`() {
        val before = registry.find("study.http.requests").timer()?.count() ?: 0L

        mvc.get("/work?millis=0").andExpect { status { isOk() } }

        assertEquals(before + 1, registry.find("study.http.requests").timer()?.count())
    }
}
