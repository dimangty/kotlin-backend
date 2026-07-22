package study.week3copy

import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Positive
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

data class CreateUserRequest(
    @field:NotBlank @field:Email val email: String,
)

data class OpenAccountRequest(
    val ownerId: UUID,
    // Формат валюты защищается и здесь для быстрой ошибки API, и CHECK-constraint в PostgreSQL.
    @field:Pattern(regexp = "[A-Z]{3}") val currency: String,
)

enum class PaymentStatus { PENDING, COMPLETED, FAILED }

data class CreatePaymentRequest(
    val accountId: UUID,
    @field:Positive val amountMinor: Long,
    val status: PaymentStatus,
)

data class UserView(val id: UUID, val email: String, val createdAt: Instant)
data class AccountView(val id: UUID, val ownerId: UUID, val currency: String, val balanceMinor: Long)
data class PaymentView(val id: UUID, val accountId: UUID, val amountMinor: Long, val status: String)

data class AccountSnapshot(
    val accountId: UUID,
    val storedBalanceMinor: Long,
    val ledgerBalanceMinor: Long,
    val paymentCount: Long,
)

data class PhysicalTuple(val accountId: UUID, val ctid: String, val xmin: Long)

@Service
class FintechService(private val jdbc: JdbcTemplate) {
    fun createUser(request: CreateUserRequest): UserView = jdbc.queryForObject(
        """
        INSERT INTO users(email)
        VALUES (lower(?))
        RETURNING id, email, created_at
        """.trimIndent(),
        { rs, _ ->
            UserView(
                id = rs.getObject("id", UUID::class.java),
                email = rs.getString("email"),
                createdAt = rs.getTimestamp("created_at").toInstant(),
            )
        },
        request.email,
    )!!

    fun openAccount(request: OpenAccountRequest): AccountView = jdbc.queryForObject(
        """
        INSERT INTO accounts(owner_id, currency)
        VALUES (?, ?)
        RETURNING id, owner_id, currency, balance_minor
        """.trimIndent(),
        { rs, _ ->
            AccountView(
                id = rs.getObject("id", UUID::class.java),
                ownerId = rs.getObject("owner_id", UUID::class.java),
                currency = rs.getString("currency").trim(),
                balanceMinor = rs.getLong("balance_minor"),
            )
        },
        request.ownerId,
        request.currency,
    )!!

    fun createPayment(request: CreatePaymentRequest): PaymentView = jdbc.queryForObject(
        """
        INSERT INTO payments(account_id, amount_minor, status)
        VALUES (?, ?, ?)
        RETURNING id, account_id, amount_minor, status
        """.trimIndent(),
        { rs, _ ->
            PaymentView(
                id = rs.getObject("id", UUID::class.java),
                accountId = rs.getObject("account_id", UUID::class.java),
                amountMinor = rs.getLong("amount_minor"),
                status = rs.getString("status"),
            )
        },
        request.accountId,
        request.amountMinor,
        request.status.name,
    )!!

    fun accountSnapshot(accountId: UUID): AccountSnapshot = jdbc.queryForObject(
        """
        WITH ledger AS (
            SELECT account_id, sum(amount_minor) AS balance
            FROM ledger_entries
            GROUP BY account_id
        ), payment_totals AS (
            SELECT account_id, count(*) AS total
            FROM payments
            GROUP BY account_id
        )
        SELECT a.id,
               a.balance_minor,
               COALESCE(ledger.balance, 0) AS ledger_balance,
               COALESCE(payment_totals.total, 0) AS payment_count
        FROM accounts a
        LEFT JOIN ledger ON ledger.account_id = a.id
        LEFT JOIN payment_totals ON payment_totals.account_id = a.id
        WHERE a.id = ?
        """.trimIndent(),
        { rs, _ ->
            AccountSnapshot(
                accountId = rs.getObject("id", UUID::class.java),
                storedBalanceMinor = rs.getLong("balance_minor"),
                ledgerBalanceMinor = rs.getLong("ledger_balance"),
                paymentCount = rs.getLong("payment_count"),
            )
        },
        accountId,
    )!!

    fun physicalTuple(accountId: UUID): PhysicalTuple = jdbc.queryForObject(
        // ctid указывает на физическое место версии строки, xmin — создавшую её transaction.
        // Эти поля полезны для лаборатории, но на них нельзя строить бизнес-контракт.
        "SELECT id, ctid::text AS ctid, xmin::text::bigint AS xmin FROM accounts WHERE id = ?",
        { rs, _ ->
            PhysicalTuple(
                accountId = rs.getObject("id", UUID::class.java),
                ctid = rs.getString("ctid"),
                xmin = rs.getLong("xmin"),
            )
        },
        accountId,
    )!!
}

@RestController
@RequestMapping("/api")
class FintechController(private val service: FintechService) {
    @PostMapping("/users")
    @ResponseStatus(HttpStatus.CREATED)
    fun createUser(@Valid @RequestBody request: CreateUserRequest) = service.createUser(request)

    @PostMapping("/accounts")
    @ResponseStatus(HttpStatus.CREATED)
    fun openAccount(@Valid @RequestBody request: OpenAccountRequest) = service.openAccount(request)

    @PostMapping("/payments")
    @ResponseStatus(HttpStatus.CREATED)
    fun createPayment(@Valid @RequestBody request: CreatePaymentRequest) = service.createPayment(request)

    @GetMapping("/accounts/{id}/snapshot")
    fun accountSnapshot(@PathVariable id: UUID) = service.accountSnapshot(id)

    @GetMapping("/accounts/{id}/physical-tuple")
    fun physicalTuple(@PathVariable id: UUID) = service.physicalTuple(id)
}
