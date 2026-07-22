package study.week16

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
        require(command.amountMinor > 0 && command.fromAccountId != command.toAccountId)
        existing(key)?.let { return it }
        val ids = listOf(command.fromAccountId, command.toAccountId).sorted()
        val rows = jdbc.query("SELECT id,balance_minor,currency FROM accounts WHERE id IN (?,?) ORDER BY id FOR UPDATE",
            { rs, _ -> Triple(rs.getObject("id", UUID::class.java), rs.getLong("balance_minor"), rs.getString("currency")) }, ids[0], ids[1])
        check(rows.size == 2 && rows.map { it.third }.distinct().size == 1) { "accounts absent or currencies differ" }
        check(rows.first { it.first == command.fromAccountId }.second >= command.amountMinor) { "insufficient funds" }

        val id = UUID.randomUUID()
        jdbc.update("INSERT INTO transfers(id,idempotency_key,from_account_id,to_account_id,amount_minor) VALUES (?,?,?,?,?)", id,key,command.fromAccountId,command.toAccountId,command.amountMinor)
        jdbc.update("UPDATE accounts SET balance_minor=balance_minor-? WHERE id=?", command.amountMinor, command.fromAccountId)
        jdbc.update("UPDATE accounts SET balance_minor=balance_minor+? WHERE id=?", command.amountMinor, command.toAccountId)
        jdbc.update("INSERT INTO ledger_entries(transfer_id,account_id,amount_minor) VALUES (?,?,?),(?,?,?)", id,command.fromAccountId,-command.amountMinor,id,command.toAccountId,command.amountMinor)
        // Audit хранит actor и operation, но не секреты/полный request body.
        jdbc.update("INSERT INTO audit_events(actor,event_type,entity_id) VALUES (?,?,?)", actor,"TRANSFER_COMPLETED",id)
        return TransferView(id, "COMPLETED")
    }

    fun existing(key: String): TransferView? = jdbc.query("SELECT id,status FROM transfers WHERE idempotency_key=?",
        { rs, _ -> TransferView(rs.getObject("id", UUID::class.java), rs.getString("status")) }, key).firstOrNull()

    fun transfer(id: UUID): TransferView = jdbc.queryForObject(
        "SELECT id,status FROM transfers WHERE id=?",
        { rs, _ -> TransferView(rs.getObject("id", UUID::class.java), rs.getString("status")) }, id,
    )!!

    fun ledger(accountId: UUID): List<LedgerEntryView> = jdbc.query(
        "SELECT id,transfer_id,amount_minor FROM ledger_entries WHERE account_id=? ORDER BY id DESC LIMIT 100",
        { rs, _ -> LedgerEntryView(rs.getLong("id"), rs.getObject("transfer_id", UUID::class.java), rs.getLong("amount_minor")) }, accountId)
}

@RestController
class FintechController(private val service: FintechService) {
    @PostMapping("/accounts") @ResponseStatus(HttpStatus.CREATED) fun account(@RequestBody body: CreateAccount) = service.createAccount(body)
    @GetMapping("/accounts/{id}") fun account(@PathVariable id: UUID) = service.account(id)
    @PostMapping("/transfers") @ResponseStatus(HttpStatus.CREATED)
    fun transfer(@RequestHeader("Idempotency-Key") key: String, @RequestHeader("X-Actor") actor: String, @RequestBody body: CreateTransfer) = service.transfer(key, body, actor)
    @GetMapping("/transfers/{id}") fun transfer(@PathVariable id: UUID) = service.transfer(id)
    @GetMapping("/accounts/{id}/ledger") fun ledger(@PathVariable id: UUID) = service.ledger(id)
}
