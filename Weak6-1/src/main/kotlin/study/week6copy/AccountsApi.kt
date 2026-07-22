package study.week6copy

import jakarta.validation.Valid
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.PositiveOrZero
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.DefaultTransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.sql.SQLException
import java.util.UUID

data class CreateAccountRequest(@field:PositiveOrZero val initialBalanceMinor: Long)
data class DebitRequest(@field:Positive val amountMinor: Long)

data class AccountView(
    val id: UUID,
    val balanceMinor: Long,
    val strategy: String,
    val attempts: Int = 1,
)

class InsufficientFundsException : RuntimeException("insufficient funds")

@Service
class AccountService(private val jdbc: JdbcTemplate) {
    fun create(request: CreateAccountRequest): AccountView = jdbc.queryForObject(
        "INSERT INTO accounts(balance_minor) VALUES (?) RETURNING id, balance_minor",
        accountRowMapper("CREATE"),
        request.initialBalanceMinor,
    )!!

    fun balance(accountId: UUID): AccountView = jdbc.queryForObject(
        "SELECT id, balance_minor FROM accounts WHERE id = ?",
        accountRowMapper("READ"),
        accountId,
    )!!

    fun atomicDebit(accountId: UUID, amountMinor: Long): AccountView {
        require(amountMinor > 0) { "amountMinor must be positive" }

        // Проверка остатка и запись находятся в одном statement. PostgreSQL повторно
        // проверит WHERE после ожидания concurrent UPDATE, поэтому lost update не возникает.
        return jdbc.query(
            """
            UPDATE accounts
            SET balance_minor = balance_minor - ?
            WHERE id = ? AND balance_minor >= ?
            RETURNING id, balance_minor
            """.trimIndent(),
            accountRowMapper("ATOMIC_UPDATE"),
            amountMinor,
            accountId,
            amountMinor,
        ).firstOrNull() ?: throw InsufficientFundsException()
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    fun lockedDebit(accountId: UUID, amountMinor: Long): AccountView {
        require(amountMinor > 0) { "amountMinor must be positive" }

        // FOR UPDATE удерживает row lock до commit. Второй запрос увидит баланс только
        // после первой транзакции и не сможет принять решение по устаревшему значению.
        val current = jdbc.queryForObject(
            "SELECT balance_minor FROM accounts WHERE id = ? FOR UPDATE",
            Long::class.java,
            accountId,
        )!!
        if (current < amountMinor) throw InsufficientFundsException()

        val newBalance = current - amountMinor
        jdbc.update("UPDATE accounts SET balance_minor = ? WHERE id = ?", newBalance, accountId)
        return AccountView(accountId, newBalance, "SELECT_FOR_UPDATE")
    }

    private fun accountRowMapper(strategy: String) = org.springframework.jdbc.core.RowMapper<AccountView> { rs, _ ->
        AccountView(
            id = rs.getObject("id", UUID::class.java),
            balanceMinor = rs.getLong("balance_minor"),
            strategy = strategy,
        )
    }
}

@Service
class SerializableDebitService(
    transactionManager: PlatformTransactionManager,
    private val jdbc: JdbcTemplate,
) {
    private val transactionDefinition = DefaultTransactionDefinition().apply {
        isolationLevel = TransactionDefinition.ISOLATION_SERIALIZABLE
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
        name = "serializable-debit"
    }
    private val transactionTemplate = TransactionTemplate(transactionManager, transactionDefinition)

    fun debit(accountId: UUID, amountMinor: Long, maxAttempts: Int = 10): AccountView {
        require(amountMinor > 0) { "amountMinor must be positive" }
        require(maxAttempts in 1..50) { "maxAttempts must be between 1 and 50" }

        var attempt = 0
        while (true) {
            attempt += 1
            try {
                // В retry входит вся бизнес-транзакция: новый snapshot, повторная проверка
                // остатка и новая запись. Повторять только UPDATE было бы логически неверно.
                return transactionTemplate.execute {
                    val current = jdbc.queryForObject(
                        "SELECT balance_minor FROM accounts WHERE id = ?",
                        Long::class.java,
                        accountId,
                    )!!
                    if (current < amountMinor) throw InsufficientFundsException()

                    jdbc.update(
                        "UPDATE accounts SET balance_minor = ? WHERE id = ?",
                        current - amountMinor,
                        accountId,
                    )
                    AccountView(accountId, current - amountMinor, "SERIALIZABLE", attempt)
                }!!
            } catch (error: RuntimeException) {
                if (!error.isSerializationFailure() || attempt >= maxAttempts) throw error

                // Backoff выполняется уже после rollback. Он ограничен и не удерживает connection/locks.
                try {
                    Thread.sleep(attempt * 5L)
                } catch (interrupted: InterruptedException) {
                    // Cancellation важнее retry: восстанавливаем флаг и прекращаем работу.
                    Thread.currentThread().interrupt()
                    throw interrupted
                }
            }
        }
    }

    private fun Throwable.isSerializationFailure(): Boolean {
        var current: Throwable? = this
        while (current != null) {
            if (current is SQLException && current.sqlState == "40001") return true
            current = current.cause
        }
        return false
    }
}

@RestController
@RequestMapping("/api/accounts")
class AccountController(
    private val accounts: AccountService,
    private val serializableDebits: SerializableDebitService,
) {
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@Valid @RequestBody request: CreateAccountRequest) = accounts.create(request)

    @GetMapping("/{id}")
    fun balance(@PathVariable id: UUID) = accounts.balance(id)

    @PostMapping("/{id}/debits/atomic")
    fun atomicDebit(@PathVariable id: UUID, @Valid @RequestBody request: DebitRequest) =
        accounts.atomicDebit(id, request.amountMinor)

    @PostMapping("/{id}/debits/locked")
    fun lockedDebit(@PathVariable id: UUID, @Valid @RequestBody request: DebitRequest) =
        accounts.lockedDebit(id, request.amountMinor)

    @PostMapping("/{id}/debits/serializable")
    fun serializableDebit(@PathVariable id: UUID, @Valid @RequestBody request: DebitRequest) =
        serializableDebits.debit(id, request.amountMinor)
}

data class ApiError(val code: String, val message: String)

@RestControllerAdvice
class AccountErrorHandler {
    @ExceptionHandler(InsufficientFundsException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun insufficientFunds(error: InsufficientFundsException) = ApiError("INSUFFICIENT_FUNDS", error.message!!)
}
