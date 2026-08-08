package study.db

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.DefaultTransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.sql.SQLException
import java.util.UUID

@Service
class IsolationService(
    private val jdbc: JdbcTemplate,
    private val transactionManager: PlatformTransactionManager,
) {
    // Дважды читает строку в одной транзакции. Во время паузы измените её из второго клиента.
    fun observe(id: UUID, isolation: DemoIsolation, pauseMillis: Long): IsolationObservation {
        require(pauseMillis in 0..15_000) { "Пауза должна быть от 0 до 15000 мс" }
        return requireNotNull(template(isolation).execute {
            val first = balance(id)
            pause(pauseMillis)
            val second = balance(id)
            IsolationObservation(isolation, first, second, first != second)
        })
    }

    // Намеренно плохой read-compute-write. Два параллельных вызова могут потерять изменение.
    fun unsafeReadModifyWrite(request: BalanceChangeRequest): AccountView = requireNotNull(
        template(DemoIsolation.READ_COMMITTED).execute {
            val oldBalance = balance(request.accountId)
            pause(request.pauseMillis)
            // Здесь записывается вычисленное значение, а не balance_minor + delta.
            jdbc.update(
                "UPDATE accounts SET balance_minor = ?, version = version + 1 WHERE id = ?",
                oldBalance + request.deltaMinor,
                request.accountId,
            )
            account(request.accountId)
        },
    )

    // Serializable обнаруживает опасный граф зависимостей и требует retry всей транзакции.
    fun serializableChange(request: BalanceChangeRequest, maxAttempts: Int = 10): AccountView {
        var attempt = 0
        while (true) {
            attempt++
            try {
                return requireNotNull(template(DemoIsolation.SERIALIZABLE).execute {
                    val oldBalance = balance(request.accountId)
                    pause(request.pauseMillis)
                    jdbc.update(
                        "UPDATE accounts SET balance_minor = ?, version = version + 1 WHERE id = ?",
                        oldBalance + request.deltaMinor,
                        request.accountId,
                    )
                    account(request.accountId)
                })
            } catch (error: RuntimeException) {
                if (!error.hasSqlState("40001") || attempt >= maxAttempts) throw error
                // Ожидаем уже после rollback: connection и блокировки больше не удерживаются.
                pause(attempt * 5L)
            }
        }
    }

    private fun template(isolation: DemoIsolation): TransactionTemplate {
        val definition = DefaultTransactionDefinition().apply {
            isolationLevel = when (isolation) {
                DemoIsolation.READ_COMMITTED -> TransactionDefinition.ISOLATION_READ_COMMITTED
                DemoIsolation.REPEATABLE_READ -> TransactionDefinition.ISOLATION_REPEATABLE_READ
                DemoIsolation.SERIALIZABLE -> TransactionDefinition.ISOLATION_SERIALIZABLE
            }
            propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
            setName("database-lab-${isolation.name.lowercase()}")
        }
        return TransactionTemplate(transactionManager, definition)
    }

    private fun balance(id: UUID): Long = jdbc.queryForObject(
        "SELECT balance_minor FROM accounts WHERE id = ?",
        Long::class.java,
        id,
    ) ?: throw NoSuchElementException("Счёт $id не найден")

    private fun account(id: UUID): AccountView = jdbc.query(
        "SELECT id, owner_name, balance_minor, version FROM accounts WHERE id = ?",
        { rs, _ ->
            AccountView(
                rs.getObject("id", UUID::class.java),
                rs.getString("owner_name"),
                rs.getLong("balance_minor"),
                rs.getLong("version"),
            )
        },
        id,
    ).first()

    private fun pause(millis: Long) {
        try {
            Thread.sleep(millis)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw interrupted
        }
    }

    // Spring оборачивает SQLException, поэтому SQLSTATE ищем по всей цепочке причин.
    private fun Throwable.hasSqlState(expected: String): Boolean {
        var current: Throwable? = this
        while (current != null) {
            if (current is SQLException && current.sqlState == expected) return true
            current = current.cause
        }
        return false
    }
}
