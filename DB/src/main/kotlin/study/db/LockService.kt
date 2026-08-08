package study.db

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class LockService(private val jdbc: JdbcTemplate) {
    @Transactional
    // Удерживает row lock заданное время, чтобы второй запрос попал в lock wait.
    fun hold(request: HoldLockRequest): AccountView {
        require(request.holdMillis in 0..15_000) { "Время удержания должно быть от 0 до 15000 мс" }
        val account = lockedAccount(request.accountId)
        pause(request.holdMillis)
        return account
    }

    @Transactional
    // Единый порядок захвата двух блокировок предотвращает deadlock A->B / B->A.
    fun lockedTransfer(request: TransferRequest): TransferView {
        require(request.amountMinor > 0) { "Сумма перевода должна быть положительной" }
        require(request.fromAccountId != request.toAccountId) { "Счета должны различаться" }
        val orderedIds = listOf(request.fromAccountId, request.toAccountId).sorted()
        val balances = jdbc.query(
            "SELECT id, balance_minor FROM accounts WHERE id IN (?, ?) ORDER BY id FOR UPDATE",
            { rs, _ -> rs.getObject("id", UUID::class.java) to rs.getLong("balance_minor") },
            orderedIds[0],
            orderedIds[1],
        ).toMap()
        check(balances.size == 2) { "Один из счетов не найден" }
        check(balances.getValue(request.fromAccountId) >= request.amountMinor) { "Средств недостаточно" }

        jdbc.update(
            "UPDATE accounts SET balance_minor = balance_minor - ?, version = version + 1 WHERE id = ?",
            request.amountMinor,
            request.fromAccountId,
        )
        jdbc.update(
            "UPDATE accounts SET balance_minor = balance_minor + ?, version = version + 1 WHERE id = ?",
            request.amountMinor,
            request.toAccountId,
        )
        val transferId = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO transfers(id, from_account_id, to_account_id, amount_minor) VALUES (?, ?, ?, ?)",
            transferId,
            request.fromAccountId,
            request.toAccountId,
            request.amountMinor,
        )
        jdbc.update(
            "INSERT INTO ledger_entries(transfer_id, account_id, amount_minor) VALUES (?, ?, ?), (?, ?, ?)",
            transferId,
            request.fromAccountId,
            -request.amountMinor,
            transferId,
            request.toAccountId,
            request.amountMinor,
        )
        return TransferView(transferId, "COMPLETED")
    }

    @Transactional
    // SKIP LOCKED не ждёт занятые задания: несколько worker-ов получают разные строки.
    fun claimJobs(limit: Int): List<JobView> {
        require(limit in 1..100) { "limit должен быть от 1 до 100" }
        return jdbc.query(
            """
            WITH claimed AS (
                SELECT id
                FROM jobs
                WHERE status = 'NEW'
                ORDER BY id
                FOR UPDATE SKIP LOCKED
                LIMIT ?
            )
            UPDATE jobs j
            SET status = 'PROCESSING'
            FROM claimed
            WHERE j.id = claimed.id
            RETURNING j.id, j.payload, j.status
            """.trimIndent(),
            { rs, _ -> JobView(rs.getLong("id"), rs.getString("payload"), rs.getString("status")) },
            limit,
        )
    }

    private fun lockedAccount(id: UUID): AccountView = jdbc.query(
        "SELECT id, owner_name, balance_minor, version FROM accounts WHERE id = ? FOR UPDATE",
        { rs, _ ->
            AccountView(
                rs.getObject("id", UUID::class.java),
                rs.getString("owner_name"),
                rs.getLong("balance_minor"),
                rs.getLong("version"),
            )
        },
        id,
    ).firstOrNull() ?: throw NoSuchElementException("Счёт $id не найден")

    private fun pause(millis: Long) {
        try {
            Thread.sleep(millis)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw interrupted
        }
    }
}
