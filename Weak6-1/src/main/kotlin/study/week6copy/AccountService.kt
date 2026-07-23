package study.week6copy

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

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
