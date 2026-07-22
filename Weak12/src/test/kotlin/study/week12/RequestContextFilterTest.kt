package study.week12

import io.micrometer.core.instrument.MeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

@SpringBootTest
@AutoConfigureMockMvc
class RequestContextFilterTest @Autowired constructor(
    private val mvc: MockMvc,
    private val registry: MeterRegistry,
) {
    @Test
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
    fun `unsafe client id is replaced and request is measured`() {
        val before = registry.find("study.http.requests").timer()?.count() ?: 0L

        val response = mvc.get("/work?millis=0") {
            header("X-Request-Id", "unsafe id with spaces")
        }.andExpect { status { isOk() } }.andReturn().response

        assertNotEquals("unsafe id with spaces", response.getHeader("X-Request-Id"))
        assertEquals(before + 1, registry.find("study.http.requests").timer()?.count())
    }
}
