package study.week9

import org.springframework.http.HttpStatus
import org.springframework.security.core.Authentication
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.*
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class Credentials(val email: String, val password: String)
data class Tokens(val accessToken: String, val refreshToken: String)
data class User(val id: UUID, val email: String, val passwordHash: String)
data class Account(val id: UUID, val ownerId: UUID, val balanceMinor: Long)

@Service
class AuthService(private val encoder: PasswordEncoder) {
    private val users = ConcurrentHashMap<String, User>()
    private val access = ConcurrentHashMap<String, UUID>()
    private val refresh = ConcurrentHashMap<String, UUID>()

    fun register(credentials: Credentials): User {
        val user = User(UUID.randomUUID(), credentials.email.lowercase(), encoder.encode(credentials.password))
        check(users.putIfAbsent(user.email, user) == null) { "email exists" }
        return user
    }
    fun login(credentials: Credentials): Tokens {
        val user = users[credentials.email.lowercase()] ?: error("invalid credentials")
        check(encoder.matches(credentials.password, user.passwordHash)) { "invalid credentials" }
        return issue(user.id)
    }
    fun rotate(oldRefresh: String): Tokens {
        // remove делает refresh одноразовым: replay старого token больше не пройдет.
        val userId = refresh.remove(oldRefresh) ?: error("refresh revoked")
        return issue(userId)
    }
    fun userForAccess(token: String?): UUID? = token?.let(access::get)
    private fun issue(userId: UUID): Tokens {
        val pair = Tokens(UUID.randomUUID().toString(), UUID.randomUUID().toString())
        access[pair.accessToken] = userId
        refresh[pair.refreshToken] = userId
        return pair
    }
}

@RestController
class Api(private val auth: AuthService) {
    private val accounts = ConcurrentHashMap<UUID, Account>()

    @GetMapping("/health") fun health() = mapOf("status" to "UP")
    @PostMapping("/auth/register") @ResponseStatus(HttpStatus.CREATED) fun register(@RequestBody body: Credentials) = auth.register(body).let { mapOf("id" to it.id, "email" to it.email) }
    @PostMapping("/auth/login") fun login(@RequestBody body: Credentials) = auth.login(body)
    @PostMapping("/auth/refresh") fun refresh(@RequestHeader("Refresh-Token") token: String) = auth.rotate(token)

    @GetMapping("/accounts/{id}")
    fun account(@PathVariable id: UUID, authentication: Authentication): Account {
        val account = accounts[id] ?: throw NoSuchElementException()
        // Authentication недостаточно: object-level authorization проверяет владельца ресурса.
        if (account.ownerId != authentication.principal) throw org.springframework.security.access.AccessDeniedException("not owner")
        return account
    }
}

