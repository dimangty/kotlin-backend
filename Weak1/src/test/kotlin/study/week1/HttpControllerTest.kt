package study.week1

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

@SpringBootTest
@AutoConfigureMockMvc
class HttpControllerTest(@Autowired private val mvc: MockMvc) {
    @Test
    fun `health is available`() {
        mvc.get("/health").andExpect { status { isOk() }; jsonPath("$.status") { value("UP") } }
    }

    @Test
    fun `echo returns request id`() {
        mvc.post("/echo") {
            contentType = org.springframework.http.MediaType.APPLICATION_JSON
            header("X-Request-Id", "r-1")
            content = """{"message":"hi"}"""
        }.andExpect { status { isOk() }; jsonPath("$.requestId") { value("r-1") } }
    }
}

