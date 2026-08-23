package study.week10

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import tools.jackson.databind.ObjectMapper

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Execution(ExecutionMode.SAME_THREAD)
class CourseCatalogIntegrationTest @Autowired constructor(
    private val mvc: MockMvc,
    private val json: ObjectMapper,
    private val jdbc: JdbcTemplate,
    private val courseRepository: CourseRepository,
    private val instructorRepository: InstructorRepository,
) {
    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:17-alpine")

        @DynamicPropertySource
        @JvmStatic
        fun database(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }

    @BeforeEach
    @AfterEach
    fun reset() {
        courseRepository.deleteAll()
        instructorRepository.deleteAll()
    }

    @Test
    fun `course lifecycle keeps the instructor relation and supports filtering`() {
        val instructorId = createInstructor("Dilip Sundarraj")
        val courseId = createCourse("Kotlin Spring Boot", "Development", instructorId)
        createCourse("WireMock for Java", "Testing", instructorId)

        mvc.get("/v1/courses") {
            param("course_name", "spring")
        }.andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(1) }
            jsonPath("$[0].id") { value(courseId) }
            jsonPath("$[0].instructorId") { value(instructorId) }
            jsonPath("$[0].instructorName") { value("Dilip Sundarraj") }
        }

        mvc.put("/v1/courses/$courseId") {
            contentType = MediaType.APPLICATION_JSON
            content = courseJson("Kotlin REST APIs", "Backend", instructorId)
        }.andExpect {
            status { isOk() }
            jsonPath("$.name") { value("Kotlin REST APIs") }
            jsonPath("$.category") { value("Backend") }
        }

        mvc.delete("/v1/courses/$courseId").andExpect { status { isNoContent() } }
        assertTrue(courseRepository.findById(courseId).isEmpty)
    }

    @Test
    fun `blank fields return a stable validation contract`() {
        mvc.post("/v1/courses") {
            header("X-Request-Id", "request-10")
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":" ","category":"","instructorId":0}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("VALIDATION_FAILED") }
            jsonPath("$.details.name") { value("must not be blank") }
            jsonPath("$.details.category") { value("must not be blank") }
            jsonPath("$.details.instructorId") { value("must be greater than 0") }
            jsonPath("$.requestId") { value("request-10") }
        }
    }

    @Test
    fun `unknown instructor returns not found and does not create a course`() {
        mvc.post("/v1/courses") {
            contentType = MediaType.APPLICATION_JSON
            content = courseJson("Kotlin", "Development", 999)
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("INSTRUCTOR_NOT_FOUND") }
        }
        assertEquals(0, courseRepository.count())
    }

    @Test
    fun `foreign key rejects a course written outside the service`() {
        assertThrows(DataIntegrityViolationException::class.java) {
            jdbc.update(
                "INSERT INTO courses(name, category, instructor_id) VALUES (?, ?, ?)",
                "Kotlin",
                "Development",
                999,
            )
        }
        assertEquals(0, courseRepository.count())
    }

    private fun createInstructor(name: String): Long {
        val response = mvc.post("/v1/instructors") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"$name"}"""
        }.andExpect { status { isCreated() } }.andReturn().response.contentAsString
        return json.readTree(response)["id"].asLong()
    }

    private fun createCourse(name: String, category: String, instructorId: Long): Long {
        val response = mvc.post("/v1/courses") {
            contentType = MediaType.APPLICATION_JSON
            content = courseJson(name, category, instructorId)
        }.andExpect { status { isCreated() } }.andReturn().response.contentAsString
        return json.readTree(response)["id"].asLong()
    }

    private fun courseJson(name: String, category: String, instructorId: Long) =
        """{"name":"$name","category":"$category","instructorId":$instructorId}"""
}
