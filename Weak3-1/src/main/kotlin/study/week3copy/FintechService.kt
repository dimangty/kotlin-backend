package study.week3copy

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.util.UUID

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
