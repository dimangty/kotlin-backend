// Проверяет аутентификацию, обновление токенов и разграничение доступа.
// Тест относится к учебному модулю недели 9 и фиксирует ожидаемое поведение кода.
package study.week9

import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.springframework.test.annotation.DirtiesContext

import org.junit.jupiter.api.TestInstance

import tools.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.util.UUID

@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
@Execution(ExecutionMode.SAME_THREAD)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class ApiSecurityTest @Autowired constructor(
    private val mvc: MockMvc,
    private val json: ObjectMapper,
) {
    @Test
    // Проверяет отклонение неаутентифицированного запроса к счёту.
    fun `account endpoint rejects unauthenticated request`() {
        mvc.get("/accounts/${UUID.randomUUID()}").andExpect { status { isUnauthorized() } }
    }

    @Test
    // Проверяет запрет доступа к счёту другого владельца.
    fun `account endpoint rejects another account owner`() {
        val owner = registerAndLogin("owner-${System.nanoTime()}@example.test")
        val stranger = registerAndLogin("stranger-${System.nanoTime()}@example.test")
        val accountId = createAccount(owner)
        mvc.get("/accounts/$accountId") { header("Authorization", "Bearer ${stranger.accessToken}") }
            .andExpect { status { isForbidden() } }
    }

    @Test
    // Проверяет выдачу счёта его аутентифицированному владельцу.
    fun `account endpoint returns account to its owner`() {
        val owner = registerAndLogin("owner-${System.nanoTime()}@example.test")
        val accountId = createAccount(owner)
        mvc.get("/accounts/$accountId") { header("Authorization", "Bearer ${owner.accessToken}") }
            .andExpect { status { isOk() }; jsonPath("$.balanceMinor") { value(1000) } }
    }

    @Test
    // Проверяет одноразовую ротацию refresh-токена.
    fun `refresh token rotates once`() {
        val tokens = registerAndLogin("rotate-${System.nanoTime()}@example.test")

        val rotated = mvc.post("/auth/refresh") { header("Refresh-Token", tokens.refreshToken) }
            .andExpect { status { isOk() } }.andReturn().response.contentAsString
        assertNotNull(json.readTree(rotated)["accessToken"].asString())

        mvc.post("/auth/refresh") { header("Refresh-Token", tokens.refreshToken) }
            .andExpect { status { isUnauthorized() } }
    }

    // Регистрирует тестового пользователя и возвращает его токены.
    private fun registerAndLogin(email: String): Tokens {
        val body = """{"email":"$email","password":"correct-horse-battery-staple"}"""
        mvc.post("/auth/register") {
            contentType = MediaType.APPLICATION_JSON
            content = body
        }.andExpect { status { isCreated() } }
        val response = mvc.post("/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = body
        }.andExpect { status { isOk() } }.andReturn().response.contentAsString
        return json.readValue(response, Tokens::class.java)
    }

    // Создаёт тестовый счёт владельца и возвращает его идентификатор.
    private fun createAccount(owner: Tokens): String {
        val body = mvc.post("/accounts") {
            header("Authorization", "Bearer ${owner.accessToken}")
            contentType = MediaType.APPLICATION_JSON
            content = """{"balanceMinor":1000}"""
        }.andExpect { status { isCreated() } }.andReturn().response.contentAsString
        return json.readTree(body)["id"].asString()
    }
}
