package study.week15

import java.sql.Connection
import java.util.UUID
import javax.sql.DataSource

class TransferService(private val dataSource: DataSource) {
    fun transfer(key: String, request: TransferRequest): TransferResult {
        require(key.isNotBlank() && key.length <= 128) { "invalid Idempotency-Key" }
        require(request.amountMinor > 0) { "amountMinor must be positive" }
        val fromId = UUID.fromString(request.from)
        val toId = UUID.fromString(request.to)
        require(fromId != toId) { "accounts must differ" }

        return inTransaction { connection ->
            findExisting(connection, key, fromId, toId, request.amountMinor)?.let { return@inTransaction it }

            // ORDER BY задаёт одинаковый порядок фактического захвата row locks для A->B и B->A.
            val balances = mutableMapOf<UUID, Long>()
            connection.prepareStatement(
                "SELECT id,balance_minor FROM accounts WHERE id IN (?,?) ORDER BY id FOR UPDATE",
            ).use { statement ->
                statement.setObject(1, fromId)
                statement.setObject(2, toId)
                statement.executeQuery().use { rows ->
                    while (rows.next()) {
                        balances[rows.getObject("id", UUID::class.java)] = rows.getLong("balance_minor")
                    }
                }
            }
            check(balances.size == 2) { "account not found" }

            // Конкурирующий запрос мог завершиться, пока этот запрос ждал account locks.
            findExisting(connection, key, fromId, toId, request.amountMinor)?.let { return@inTransaction it }
            check(balances.getValue(fromId) >= request.amountMinor) { "insufficient funds" }

            val transferId = UUID.randomUUID()
            val inserted = connection.prepareStatement(
                """
                INSERT INTO transfers(id,idempotency_key,from_account_id,to_account_id,amount_minor)
                VALUES (?,?,?,?,?)
                ON CONFLICT (idempotency_key) DO NOTHING
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, transferId)
                statement.setString(2, key)
                statement.setObject(3, fromId)
                statement.setObject(4, toId)
                statement.setLong(5, request.amountMinor)
                statement.executeUpdate()
            }
            if (inserted == 0) {
                return@inTransaction findExisting(connection, key, fromId, toId, request.amountMinor)
                    ?: error("idempotency conflict without a stored transfer")
            }

            connection.prepareStatement("UPDATE accounts SET balance_minor=balance_minor + ? WHERE id=?").use { statement ->
                statement.setLong(1, -request.amountMinor)
                statement.setObject(2, fromId)
                check(statement.executeUpdate() == 1)
                statement.setLong(1, request.amountMinor)
                statement.setObject(2, toId)
                check(statement.executeUpdate() == 1)
            }
            connection.prepareStatement(
                "INSERT INTO ledger_entries(transfer_id,account_id,amount_minor) VALUES (?,?,?),(?,?,?)",
            ).use { statement ->
                statement.setObject(1, transferId)
                statement.setObject(2, fromId)
                statement.setLong(3, -request.amountMinor)
                statement.setObject(4, transferId)
                statement.setObject(5, toId)
                statement.setLong(6, request.amountMinor)
                check(statement.executeUpdate() == 2)
            }
            TransferResult(transferId.toString(), "COMPLETED")
        }
    }

    private fun findExisting(
        connection: Connection,
        key: String,
        fromId: UUID,
        toId: UUID,
        amountMinor: Long,
    ): TransferResult? = connection.prepareStatement(
        "SELECT id,status,from_account_id,to_account_id,amount_minor FROM transfers WHERE idempotency_key=?",
    ).use { statement ->
        statement.setString(1, key)
        statement.executeQuery().use { rows ->
            if (!rows.next()) return@use null
            val stored = StoredTransfer(
                TransferResult(rows.getObject("id", UUID::class.java).toString(), rows.getString("status")),
                rows.getObject("from_account_id", UUID::class.java),
                rows.getObject("to_account_id", UUID::class.java),
                rows.getLong("amount_minor"),
            )
            require(stored.fromId == fromId && stored.toId == toId && stored.amountMinor == amountMinor) {
                "idempotency key was already used for another request"
            }
            stored.result
        }
    }

    private fun <T> inTransaction(block: (Connection) -> T): T = dataSource.connection.use { connection ->
        connection.autoCommit = false
        try {
            val result = block(connection)
            connection.commit()
            result
        } catch (error: Exception) {
            connection.rollback()
            throw error
        }
    }
}
