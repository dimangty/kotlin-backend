package study.week16

import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.EmptyResultDataAccessException
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import java.util.UUID

data class CreateAccount(val ownerId: UUID, val currency: String, val initialBalanceMinor: Long = 0)
data class AccountView(val id: UUID, val ownerId: UUID, val currency: String, val balanceMinor: Long)
data class CreateTransfer(val fromAccountId: UUID, val toAccountId: UUID, val amountMinor: Long)
data class TransferView(val id: UUID, val status: String)
data class LedgerEntryView(val id: Long, val transferId: UUID, val amountMinor: Long)
data class ApiError(val code: String, val message: String)
private data class StoredTransfer(
    val view: TransferView,
    val fromAccountId: UUID,
    val toAccountId: UUID,
    val amountMinor: Long,
)

@Service
class FintechService(private val jdbc: JdbcTemplate) {
    fun createAccount(command: CreateAccount): AccountView {
        require(command.currency.matches(Regex("[A-Z]{3}")))
        require(command.initialBalanceMinor >= 0)
        val id = UUID.randomUUID()
        jdbc.update("INSERT INTO accounts(id,owner_id,currency,balance_minor) VALUES (?,?,?,?)", id, command.ownerId, command.currency, command.initialBalanceMinor)
        return AccountView(id, command.ownerId, command.currency, command.initialBalanceMinor)
    }

    fun account(id: UUID): AccountView = jdbc.queryForObject(
        "SELECT id,owner_id,currency,balance_minor FROM accounts WHERE id=?",
        { rs, _ -> AccountView(rs.getObject("id", UUID::class.java), rs.getObject("owner_id", UUID::class.java), rs.getString("currency"), rs.getLong("balance_minor")) }, id,
    )!!

    @Transactional
    fun transfer(key: String, command: CreateTransfer, actor: String): TransferView {
        require(key.isNotBlank() && key.length <= 128) { "invalid Idempotency-Key" }
        require(actor.isNotBlank() && actor.length <= 128) { "invalid actor" }
        require(command.amountMinor > 0 && command.fromAccountId != command.toAccountId)
        existing(key, command)?.let { return it }
        val ids = listOf(command.fromAccountId, command.toAccountId).sorted()
        val rows = jdbc.query("SELECT id,balance_minor,currency FROM accounts WHERE id IN (?,?) ORDER BY id FOR UPDATE",
            { rs, _ -> Triple(rs.getObject("id", UUID::class.java), rs.getLong("balance_minor"), rs.getString("currency")) }, ids[0], ids[1])
        check(rows.size == 2 && rows.map { it.third }.distinct().size == 1) { "accounts absent or currencies differ" }

        // Повторная проверка после ordered locks закрывает race двух одинаковых POST.
        existing(key, command)?.let { return it }
        check(rows.first { it.first == command.fromAccountId }.second >= command.amountMinor) { "insufficient funds" }

        val id = UUID.randomUUID()
        val inserted = jdbc.update(
            """
            INSERT INTO transfers(id,idempotency_key,from_account_id,to_account_id,amount_minor)
            VALUES (?,?,?,?,?)
            ON CONFLICT (idempotency_key) DO NOTHING
            """.trimIndent(),
            id, key, command.fromAccountId, command.toAccountId, command.amountMinor,
        )
        if (inserted == 0) {
            return existing(key, command) ?: error("idempotency conflict without a stored transfer")
        }
        jdbc.update("UPDATE accounts SET balance_minor=balance_minor-? WHERE id=?", command.amountMinor, command.fromAccountId)
        jdbc.update("UPDATE accounts SET balance_minor=balance_minor+? WHERE id=?", command.amountMinor, command.toAccountId)
        jdbc.update("INSERT INTO ledger_entries(transfer_id,account_id,amount_minor) VALUES (?,?,?),(?,?,?)", id,command.fromAccountId,-command.amountMinor,id,command.toAccountId,command.amountMinor)
        // Audit хранит actor и operation, но не секреты/полный request body.
        jdbc.update("INSERT INTO audit_events(actor,event_type,entity_id) VALUES (?,?,?)", actor,"TRANSFER_COMPLETED",id)
        return TransferView(id, "COMPLETED")
    }

    private fun existing(key: String, command: CreateTransfer): TransferView? {
        val stored = jdbc.query(
            """
            SELECT id,status,from_account_id,to_account_id,amount_minor
            FROM transfers WHERE idempotency_key=?
            """.trimIndent(),
            { rs, _ ->
                StoredTransfer(
                    TransferView(rs.getObject("id", UUID::class.java), rs.getString("status")),
                    rs.getObject("from_account_id", UUID::class.java),
                    rs.getObject("to_account_id", UUID::class.java),
                    rs.getLong("amount_minor"),
                )
            },
            key,
        ).firstOrNull() ?: return null
        require(
            stored.fromAccountId == command.fromAccountId &&
                stored.toAccountId == command.toAccountId &&
                stored.amountMinor == command.amountMinor,
        ) { "idempotency key was already used for another request" }
        return stored.view
    }

    fun transfer(id: UUID): TransferView = jdbc.queryForObject(
        "SELECT id,status FROM transfers WHERE id=?",
        { rs, _ -> TransferView(rs.getObject("id", UUID::class.java), rs.getString("status")) }, id,
    )!!

    fun ledger(accountId: UUID, cursor: Long?, limit: Int): List<LedgerEntryView> {
        require(limit in 1..100) { "limit must be between 1 and 100" }
        require(cursor == null || cursor > 0) { "cursor must be positive" }
        return jdbc.query(
            """
            SELECT id,transfer_id,amount_minor
            FROM ledger_entries
            WHERE account_id=? AND id < ?
            ORDER BY id DESC
            LIMIT ?
            """.trimIndent(),
            { rs, _ -> LedgerEntryView(rs.getLong("id"), rs.getObject("transfer_id", UUID::class.java), rs.getLong("amount_minor")) },
            accountId,
            cursor ?: Long.MAX_VALUE,
            limit,
        )
    }
}

@RestController
class FintechController(private val service: FintechService) {
    @PostMapping("/accounts") @ResponseStatus(HttpStatus.CREATED) fun account(@RequestBody body: CreateAccount) = service.createAccount(body)
    @GetMapping("/accounts/{id}") fun account(@PathVariable id: UUID) = service.account(id)
    @PostMapping("/transfers") @ResponseStatus(HttpStatus.CREATED)
    fun transfer(@RequestHeader("Idempotency-Key") key: String, @RequestHeader("X-Actor") actor: String, @RequestBody body: CreateTransfer) = service.transfer(key, body, actor)
    @GetMapping("/transfers/{id}") fun transfer(@PathVariable id: UUID) = service.transfer(id)
    @GetMapping("/accounts/{id}/ledger")
    fun ledger(
        @PathVariable id: UUID,
        @RequestParam(required = false) cursor: Long?,
        @RequestParam(defaultValue = "50") limit: Int,
    ) = service.ledger(id, cursor, limit)
}

@RestControllerAdvice
class FintechErrorHandler {
    @ExceptionHandler(IllegalArgumentException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun invalid(error: IllegalArgumentException) = ApiError("INVALID_REQUEST", error.message ?: "invalid request")

    @ExceptionHandler(EmptyResultDataAccessException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun missing() = ApiError("RESOURCE_NOT_FOUND", "resource not found")

    @ExceptionHandler(IllegalStateException::class, DataIntegrityViolationException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun conflict() = ApiError("OPERATION_REJECTED", "operation violates a business invariant")
}
