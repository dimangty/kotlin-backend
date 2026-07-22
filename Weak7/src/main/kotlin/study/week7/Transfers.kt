package study.week7

import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import java.util.UUID

data class TransferRequest(val fromAccountId: UUID, val toAccountId: UUID, val amountMinor: Long)
data class TransferResponse(val id: UUID, val status: String)

@Service
class TransferService(private val jdbc: JdbcTemplate) {
    @Transactional
    fun transfer(key: String, request: TransferRequest): TransferResponse {
        require(request.amountMinor > 0) { "amountMinor must be positive" }
        require(request.fromAccountId != request.toAccountId) { "accounts must differ" }

        findExisting(key)?.let { return it }

        // Единый порядок lock acquisition предотвращает цикл A->B / B->A.
        val orderedIds = listOf(request.fromAccountId, request.toAccountId).sorted()
        val balances = jdbc.query(
            "SELECT id, balance_minor FROM accounts WHERE id IN (?, ?) ORDER BY id FOR UPDATE",
            { rs, _ -> rs.getObject("id", UUID::class.java) to rs.getLong("balance_minor") },
            orderedIds[0], orderedIds[1],
        ).toMap()
        check(balances.size == 2) { "account not found" }
        check(balances.getValue(request.fromAccountId) >= request.amountMinor) { "insufficient funds" }

        val transferId = UUID.randomUUID()
        try {
            // UNIQUE(key) превращает idempotency из соглашения приложения в DB invariant.
            jdbc.update("INSERT INTO transfers(id, idempotency_key, from_account_id, to_account_id, amount_minor) VALUES (?, ?, ?, ?, ?)",
                transferId, key, request.fromAccountId, request.toAccountId, request.amountMinor)
        } catch (error: DataIntegrityViolationException) {
            return findExisting(key) ?: throw error
        }

        jdbc.update("UPDATE accounts SET balance_minor = balance_minor - ? WHERE id = ?", request.amountMinor, request.fromAccountId)
        jdbc.update("UPDATE accounts SET balance_minor = balance_minor + ? WHERE id = ?", request.amountMinor, request.toAccountId)
        jdbc.update("INSERT INTO ledger_entries(transfer_id, account_id, amount_minor) VALUES (?, ?, ?), (?, ?, ?)",
            transferId, request.fromAccountId, -request.amountMinor,
            transferId, request.toAccountId, request.amountMinor)
        return TransferResponse(transferId, "COMPLETED")
    }

    fun findExisting(key: String): TransferResponse? = jdbc.query(
        "SELECT id, status FROM transfers WHERE idempotency_key = ?",
        { rs, _ -> TransferResponse(rs.getObject("id", UUID::class.java), rs.getString("status")) }, key,
    ).firstOrNull()
}

@RestController
class TransferController(private val service: TransferService) {
    @PostMapping("/transfers")
    @ResponseStatus(HttpStatus.CREATED)
    fun transfer(@RequestHeader("Idempotency-Key") key: String, @RequestBody request: TransferRequest) = service.transfer(key, request)
}

