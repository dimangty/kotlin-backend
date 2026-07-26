// Проверяет HTTP-контракт контроллера заметок.
// Тест относится к учебному модулю недели 2 и фиксирует ожидаемое поведение кода.
package study.week2

import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.springframework.test.annotation.DirtiesContext

import org.junit.jupiter.api.TestInstance

import tools.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put

@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
@Execution(ExecutionMode.SAME_THREAD)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class NoteControllerTest @Autowired constructor(
    private val mvc: MockMvc,
    private val json: ObjectMapper,
) {
    @Test
    fun `malformed note id returns a client error`() {
        mvc.get("/notes/not-a-uuid").andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("MALFORMED_REQUEST") }
        }
    }

    @Test
    fun `blank title fails validation`() {
        mvc.post("/notes") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"title":" "}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("VALIDATION_FAILED") }
        }
    }

    @Test
    fun `existing note can be updated`() {
        val id = createNote()
        mvc.put("/notes/$id") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"title":"REST v2","body":"second","version":0}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.version") { value(1) }
        }
    }

    @Test
    fun `stale note version returns a conflict`() {
        val id = createNote()
        mvc.put("/notes/$id") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"title":"REST v2","version":0}"""
        }.andExpect { status { isOk() } }
        mvc.put("/notes/$id") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"title":"stale","version":0}"""
        }.andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("STALE_VERSION") }
        }
    }

    @Test
    fun `deleted note is no longer available`() {
        val id = createNote()
        mvc.delete("/notes/$id").andExpect { status { isNoContent() } }
        mvc.get("/notes/$id").andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("NOTE_NOT_FOUND") }
        }
    }

    private fun createNote(): String {
        val body = mvc.post("/notes") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"title":"REST","body":"first"}"""
        }.andExpect { status { isCreated() } }.andReturn().response.contentAsString
        return json.readTree(body)["id"].asString()
    }
}
