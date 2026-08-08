package study.db

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class AccountService(private val jdbc: JdbcTemplate) {
    // Создаёт счёт. CHECK в БД дублирует важный инвариант и защищает от любого клиента.
    fun create(request: CreateAccountRequest): AccountView {
        val id = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO accounts(id, owner_name, balance_minor) VALUES (?, ?, ?)",
            id,
            request.ownerName,
            request.initialBalanceMinor,
        )
        return get(id)
    }

    // Читает счёт вне явной транзакции: один SQL statement сам является транзакцией.
    fun get(id: UUID): AccountView = jdbc.query(
        "SELECT id, owner_name, balance_minor, version FROM accounts WHERE id = ?",
        accountRowMapper,
        id,
    ).firstOrNull() ?: throw NoSuchElementException("Счёт $id не найден")

    // Проверка остатка и изменение находятся в одном statement, поэтому lost update невозможен.
    fun atomicDebit(id: UUID, amountMinor: Long): AccountView {
        require(amountMinor > 0) { "Сумма списания должна быть положительной" }
        val changed = jdbc.update(
            """
            UPDATE accounts
            SET balance_minor = balance_minor - ?, version = version + 1
            WHERE id = ? AND balance_minor >= ?
            """.trimIndent(),
            amountMinor,
            id,
            amountMinor,
        )
        check(changed == 1) { "Счёт не найден или средств недостаточно" }
        return get(id)
    }

    @Transactional
    // Демонстрирует ACID: debit, credit, transfer и ledger фиксируются одной транзакцией.
    fun transfer(request: TransferRequest): TransferView {
        require(request.amountMinor > 0) { "Сумма перевода должна быть положительной" }
        require(request.fromAccountId != request.toAccountId) { "Счета должны различаться" }

        val debited = jdbc.update(
            """
            UPDATE accounts
            SET balance_minor = balance_minor - ?, version = version + 1
            WHERE id = ? AND balance_minor >= ?
            """.trimIndent(),
            request.amountMinor,
            request.fromAccountId,
            request.amountMinor,
        )
        check(debited == 1) { "Счёт списания не найден или средств недостаточно" }

        // Искусственная ошибка показывает Atomicity: Spring откатит уже выполненный UPDATE.
        if (request.failAfterDebit) error("Учебная ошибка после списания")

        val credited = jdbc.update(
            "UPDATE accounts SET balance_minor = balance_minor + ?, version = version + 1 WHERE id = ?",
            request.amountMinor,
            request.toAccountId,
        )
        check(credited == 1) { "Счёт зачисления не найден" }

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

    private val accountRowMapper = org.springframework.jdbc.core.RowMapper<AccountView> { rs, _ ->
        AccountView(
            id = rs.getObject("id", UUID::class.java),
            ownerName = rs.getString("owner_name"),
            balanceMinor = rs.getLong("balance_minor"),
            version = rs.getLong("version"),
        )
    }
}
