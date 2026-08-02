// Выдаёт, обновляет и проверяет токены аутентификации.
// Компонент относится к учебному модулю недели 9 и раскрывает его основной пример.
package study.week9

import org.springframework.http.HttpStatus
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Service
class AuthService(private val encoder: PasswordEncoder) {
    private val users = ConcurrentHashMap<String, User>()
    private val access = ConcurrentHashMap<String, TokenGrant>()
    private val refresh = ConcurrentHashMap<String, TokenGrant>()

    // Регистрирует пользователя с нормализованным email и хешированным паролем.
    fun register(credentials: Credentials): User {
        val passwordHash = requireNotNull(encoder.encode(credentials.password)) {
            "password encoder returned no hash"
        }
        val user = User(UUID.randomUUID(), credentials.email.lowercase(), passwordHash)
        if (users.putIfAbsent(user.email, user) != null) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "email exists")
        }
        return user
    }
    // Проверяет учётные данные и выдаёт токены пользователя.
    fun login(credentials: Credentials): Tokens {
        val user = users[credentials.email.lowercase()]
        if (user == null || !encoder.matches(credentials.password, user.passwordHash)) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid credentials")
        }
        return issue(user.id)
    }
    // Одноразово заменяет refresh-токен новой парой токенов.
    fun rotate(oldRefresh: String): Tokens {
        // remove делает refresh одноразовым: replay старого token больше не пройдет.
        val grant = refresh.remove(oldRefresh)
        if (grant == null || !grant.expiresAt.isAfter(Instant.now())) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "refresh revoked or expired")
        }
        return issue(grant.userId)
    }
    // Определяет пользователя по действующему access-токену.
    fun userForAccess(token: String?): UUID? {
        val value = token ?: return null
        val grant = access[value] ?: return null
        if (!grant.expiresAt.isAfter(Instant.now())) {
            access.remove(value, grant)
            return null
        }
        return grant.userId
    }
    // Выпускает и сохраняет новую пару access- и refresh-токенов.
    private fun issue(userId: UUID): Tokens {
        val pair = Tokens(UUID.randomUUID().toString(), UUID.randomUUID().toString())
        val now = Instant.now()
        access[pair.accessToken] = TokenGrant(userId, now.plusSeconds(15 * 60))
        refresh[pair.refreshToken] = TokenGrant(userId, now.plusSeconds(30L * 24 * 60 * 60))
        return pair
    }
}
