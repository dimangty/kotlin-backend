package study.week9

import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.PositiveOrZero
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.security.core.Authentication
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class Credentials(
    @field:Email @field:NotBlank val email: String,
    @field:Size(min = 12, max = 128) val password: String,
)
data class Tokens(val accessToken: String, val refreshToken: String)
data class User(val id: UUID, val email: String, val passwordHash: String)
data class Account(val id: UUID, val ownerId: UUID, val balanceMinor: Long)
data class CreateAccount(@field:PositiveOrZero val balanceMinor: Long = 0)
private data class TokenGrant(val userId: UUID, val expiresAt: Instant)

@Service
class AuthService(private val encoder: PasswordEncoder) {
    private val users = ConcurrentHashMap<String, User>()
    private val access = ConcurrentHashMap<String, TokenGrant>()
    private val refresh = ConcurrentHashMap<String, TokenGrant>()

    fun register(credentials: Credentials): User {
        val user = User(UUID.randomUUID(), credentials.email.lowercase(), encoder.encode(credentials.password))
        if (users.putIfAbsent(user.email, user) != null) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "email exists")
        }
        return user
    }
    fun login(credentials: Credentials): Tokens {
        val user = users[credentials.email.lowercase()]
        if (user == null || !encoder.matches(credentials.password, user.passwordHash)) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid credentials")
        }
        return issue(user.id)
    }
    fun rotate(oldRefresh: String): Tokens {
        // remove делает refresh одноразовым: replay старого token больше не пройдет.
        val grant = refresh.remove(oldRefresh)
        if (grant == null || !grant.expiresAt.isAfter(Instant.now())) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "refresh revoked or expired")
        }
        return issue(grant.userId)
    }
    fun userForAccess(token: String?): UUID? {
        val value = token ?: return null
        val grant = access[value] ?: return null
        if (!grant.expiresAt.isAfter(Instant.now())) {
            access.remove(value, grant)
            return null
        }
        return grant.userId
    }
    private fun issue(userId: UUID): Tokens {
        val pair = Tokens(UUID.randomUUID().toString(), UUID.randomUUID().toString())
        val now = Instant.now()
        access[pair.accessToken] = TokenGrant(userId, now.plusSeconds(15 * 60))
        refresh[pair.refreshToken] = TokenGrant(userId, now.plusSeconds(30L * 24 * 60 * 60))
        return pair
    }
}

@RestController
class Api(private val auth: AuthService) {
    private val accounts = ConcurrentHashMap<UUID, Account>()

    @GetMapping("/health") fun health() = mapOf("status" to "UP")
    @PostMapping("/auth/register") @ResponseStatus(HttpStatus.CREATED) fun register(@Valid @RequestBody body: Credentials) = auth.register(body).let { mapOf("id" to it.id, "email" to it.email) }
    @PostMapping("/auth/login") fun login(@Valid @RequestBody body: Credentials) = auth.login(body)
    @PostMapping("/auth/refresh") fun refresh(@RequestHeader("Refresh-Token") token: String) = auth.rotate(token)

    @PostMapping("/accounts")
    @ResponseStatus(HttpStatus.CREATED)
    fun createAccount(authentication: Authentication, @Valid @RequestBody body: CreateAccount): Account {
        val account = Account(UUID.randomUUID(), authentication.principal as UUID, body.balanceMinor)
        accounts[account.id] = account
        return account
    }

    @GetMapping("/accounts/{id}")
    fun account(@PathVariable id: UUID, authentication: Authentication): Account {
        val account = accounts[id] ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "account not found")
        // Authentication недостаточно: object-level authorization проверяет владельца ресурса.
        if (account.ownerId != authentication.principal) throw org.springframework.security.access.AccessDeniedException("not owner")
        return account
    }
}
