package study.week9

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

@SpringBootTest
@AutoConfigureMockMvc
class ApiSecurityTest @Autowired constructor(
    private val mvc: MockMvc,
    private val json: ObjectMapper,
) {
    @Test
    fun `account is visible only to its owner`() {
        val owner = registerAndLogin("owner-${System.nanoTime()}@example.test")
        val stranger = registerAndLogin("stranger-${System.nanoTime()}@example.test")

        val account = mvc.post("/accounts") {
            header("Authorization", "Bearer ${owner.accessToken}")
            contentType = MediaType.APPLICATION_JSON
            content = """{"balanceMinor":1000}"""
        }.andExpect { status { isCreated() } }.andReturn().response.contentAsString
        val accountId = json.readTree(account)["id"].asString()

        mvc.get("/accounts/$accountId").andExpect { status { isUnauthorized() } }
        mvc.get("/accounts/$accountId") { header("Authorization", "Bearer ${stranger.accessToken}") }
            .andExpect { status { isForbidden() } }
        mvc.get("/accounts/$accountId") { header("Authorization", "Bearer ${owner.accessToken}") }
            .andExpect { status { isOk() }; jsonPath("$.balanceMinor") { value(1000) } }
    }

    @Test
    fun `refresh token rotates once`() {
        val tokens = registerAndLogin("rotate-${System.nanoTime()}@example.test")

        val rotated = mvc.post("/auth/refresh") { header("Refresh-Token", tokens.refreshToken) }
            .andExpect { status { isOk() } }.andReturn().response.contentAsString
        assertNotNull(json.readTree(rotated)["accessToken"].asString())

        mvc.post("/auth/refresh") { header("Refresh-Token", tokens.refreshToken) }
            .andExpect { status { isUnauthorized() } }
    }

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
}
