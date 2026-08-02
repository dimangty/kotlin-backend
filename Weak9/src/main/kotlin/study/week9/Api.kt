// Объявляет защищённые HTTP-эндпоинты учебного приложения.
// Компонент относится к учебному модулю недели 9 и раскрывает его основной пример.
package study.week9

import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@RestController
class Api(private val auth: AuthService) {
    private val accounts = ConcurrentHashMap<UUID, Account>()

    // Возвращает состояние доступности приложения.
    @GetMapping("/health") fun health() = mapOf("status" to "UP")
    // Регистрирует нового пользователя по переданным учётным данным.
    @PostMapping("/auth/register") @ResponseStatus(HttpStatus.CREATED) fun register(@Valid @RequestBody body: Credentials) = auth.register(body).let { mapOf("id" to it.id, "email" to it.email) }
    // Аутентифицирует пользователя и выдаёт пару токенов.
    @PostMapping("/auth/login") fun login(@Valid @RequestBody body: Credentials) = auth.login(body)
    // Обновляет пару токенов по одноразовому refresh-токену.
    @PostMapping("/auth/refresh") fun refresh(@RequestHeader("Refresh-Token") token: String) = auth.rotate(token)

    @PostMapping("/accounts")
    @ResponseStatus(HttpStatus.CREATED)
    // Создаёт счёт, принадлежащий аутентифицированному пользователю.
    fun createAccount(authentication: Authentication, @Valid @RequestBody body: CreateAccount): Account {
        val account = Account(UUID.randomUUID(), authentication.principal as UUID, body.balanceMinor)
        accounts[account.id] = account
        return account
    }

    @GetMapping("/accounts/{id}")
    // Возвращает счёт только его аутентифицированному владельцу.
    fun account(@PathVariable id: UUID, authentication: Authentication): Account {
        val account = accounts[id] ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "account not found")
        // Authentication недостаточно: object-level authorization проверяет владельца ресурса.
        if (account.ownerId != authentication.principal) throw org.springframework.security.access.AccessDeniedException("not owner")
        return account
    }
}
