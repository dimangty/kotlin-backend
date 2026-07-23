package study.week6copy

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.DefaultTransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.sql.SQLException
import java.util.UUID

@Service
class SerializableDebitService(
    transactionManager: PlatformTransactionManager,
    private val jdbc: JdbcTemplate,
) {
    private val transactionDefinition = DefaultTransactionDefinition().apply {
        isolationLevel = TransactionDefinition.ISOLATION_SERIALIZABLE
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
        name = "serializable-debit"
    }
    private val transactionTemplate = TransactionTemplate(transactionManager, transactionDefinition)

    fun debit(accountId: UUID, amountMinor: Long, maxAttempts: Int = 10): AccountView {
        require(amountMinor > 0) { "amountMinor must be positive" }
        require(maxAttempts in 1..50) { "maxAttempts must be between 1 and 50" }

        var attempt = 0
        while (true) {
            attempt += 1
            try {
                // В retry входит вся бизнес-транзакция: новый snapshot, повторная проверка
                // остатка и новая запись. Повторять только UPDATE было бы логически неверно.
                return transactionTemplate.execute {
                    val current = jdbc.queryForObject(
                        "SELECT balance_minor FROM accounts WHERE id = ?",
                        Long::class.java,
                        accountId,
                    )!!
                    if (current < amountMinor) throw InsufficientFundsException()

                    jdbc.update(
                        "UPDATE accounts SET balance_minor = ? WHERE id = ?",
                        current - amountMinor,
                        accountId,
                    )
                    AccountView(accountId, current - amountMinor, "SERIALIZABLE", attempt)
                }!!
            } catch (error: RuntimeException) {
                if (!error.isSerializationFailure() || attempt >= maxAttempts) throw error

                // Backoff выполняется уже после rollback. Он ограничен и не удерживает connection/locks.
                try {
                    Thread.sleep(attempt * 5L)
                } catch (interrupted: InterruptedException) {
                    // Cancellation важнее retry: восстанавливаем флаг и прекращаем работу.
                    Thread.currentThread().interrupt()
                    throw interrupted
                }
            }
        }
    }

    private fun Throwable.isSerializationFailure(): Boolean {
        var current: Throwable? = this
        while (current != null) {
            if (current is SQLException && current.sqlState == "40001") return true
            current = current.cause
        }
        return false
    }
}
