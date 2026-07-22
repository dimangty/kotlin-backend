package study.week7

import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import java.util.UUID

data class TransferRequest(val fromAccountId: UUID, val toAccountId: UUID, val amountMinor: Long)
data class TransferResponse(val id: UUID, val status: String)
private data class StoredTransfer(
    val response: TransferResponse,
    val fromAccountId: UUID,
    val toAccountId: UUID,
    val amountMinor: Long,
)

@Service
class TransferService(private val jdbc: JdbcTemplate) {
    @Transactional
    fun transfer(key: String, request: TransferRequest): TransferResponse {
        require(key.isNotBlank() && key.length <= 128) { "invalid Idempotency-Key" }
        require(request.amountMinor > 0) { "amountMinor must be positive" }
        require(request.fromAccountId != request.toAccountId) { "accounts must differ" }

        findExisting(key, request)?.let { return it }

        // Единый порядок lock acquisition предотвращает цикл A->B / B->A.
        val orderedIds = listOf(request.fromAccountId, request.toAccountId).sorted()
        val balances = jdbc.query(
            "SELECT id, balance_minor FROM accounts WHERE id IN (?, ?) ORDER BY id FOR UPDATE",
            { rs, _ -> rs.getObject("id", UUID::class.java) to rs.getLong("balance_minor") },
            orderedIds[0], orderedIds[1],
        ).toMap()
        check(balances.size == 2) { "account not found" }

        // Повторная проверка после locks закрывает race между первым SELECT и INSERT.
        findExisting(key, request)?.let { return it }
        check(balances.getValue(request.fromAccountId) >= request.amountMinor) { "insufficient funds" }

        val transferId = UUID.randomUUID()
        // ON CONFLICT не переводит PostgreSQL transaction в aborted state, в отличие
        // от перехвата unique violation внутри @Transactional метода.
        val inserted = jdbc.update(
            """
            INSERT INTO transfers(id, idempotency_key, from_account_id, to_account_id, amount_minor)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (idempotency_key) DO NOTHING
            """.trimIndent(),
            transferId, key, request.fromAccountId, request.toAccountId, request.amountMinor,
        )
        if (inserted == 0) {
            return findExisting(key, request) ?: error("idempotency conflict without a stored transfer")
        }

        jdbc.update("UPDATE accounts SET balance_minor = balance_minor - ? WHERE id = ?", request.amountMinor, request.fromAccountId)
        jdbc.update("UPDATE accounts SET balance_minor = balance_minor + ? WHERE id = ?", request.amountMinor, request.toAccountId)
        jdbc.update("INSERT INTO ledger_entries(transfer_id, account_id, amount_minor) VALUES (?, ?, ?), (?, ?, ?)",
            transferId, request.fromAccountId, -request.amountMinor,
            transferId, request.toAccountId, request.amountMinor)
        return TransferResponse(transferId, "COMPLETED")
    }

    private fun findExisting(key: String, request: TransferRequest): TransferResponse? {
        val stored = jdbc.query(
            """
            SELECT id, status, from_account_id, to_account_id, amount_minor
            FROM transfers WHERE idempotency_key = ?
            """.trimIndent(),
            { rs, _ ->
                StoredTransfer(
                    TransferResponse(rs.getObject("id", UUID::class.java), rs.getString("status")),
                    rs.getObject("from_account_id", UUID::class.java),
                    rs.getObject("to_account_id", UUID::class.java),
                    rs.getLong("amount_minor"),
                )
            },
            key,
        ).firstOrNull() ?: return null
        require(
            stored.fromAccountId == request.fromAccountId &&
                stored.toAccountId == request.toAccountId &&
                stored.amountMinor == request.amountMinor,
        ) { "idempotency key was already used for another request" }
        return stored.response
    }
}

@RestController
class TransferController(private val service: TransferService) {
    @PostMapping("/transfers")
    @ResponseStatus(HttpStatus.CREATED)
    fun transfer(@RequestHeader("Idempotency-Key") key: String, @RequestBody request: TransferRequest) = service.transfer(key, request)
}
