package study.week2

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put

@SpringBootTest
@AutoConfigureMockMvc
class NoteControllerTest @Autowired constructor(
    private val mvc: MockMvc,
    private val json: ObjectMapper,
) {
    @Test
    fun `crud exposes stable success and error contracts`() {
        mvc.get("/notes/not-a-uuid").andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("MALFORMED_REQUEST") }
        }

        mvc.post("/notes") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"title":" "}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("VALIDATION_FAILED") }
        }

        val created = mvc.post("/notes") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"title":"REST","body":"first"}"""
        }.andExpect { status { isCreated() } }.andReturn().response.contentAsString
        val id = json.readTree(created)["id"].asText()

        mvc.put("/notes/$id") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"title":"REST v2","body":"second","version":0}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.version") { value(1) }
        }

        mvc.put("/notes/$id") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"title":"stale","version":0}"""
        }.andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("STALE_VERSION") }
        }

        mvc.delete("/notes/$id").andExpect { status { isNoContent() } }
        mvc.get("/notes/$id").andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("NOTE_NOT_FOUND") }
        }
    }
}
