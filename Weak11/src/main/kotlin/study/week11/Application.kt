package study.week11

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

data class Payment(val idempotencyKey: String, val amountMinor: Long, val status: Status)
enum class Status { RESERVED, COMPLETED, FAILED }

interface PaymentRepository {
    fun find(key: String): Payment?
    fun save(payment: Payment): Payment
}

class MemoryRepository : PaymentRepository {
    private val values = ConcurrentHashMap<String, Payment>()
    override fun find(key: String) = values[key]
    override fun save(payment: Payment): Payment { values[payment.idempotencyKey] = payment; return payment }
}

interface ExternalGateway { suspend fun charge(key: String, amountMinor: Long): String }

class DemoGateway : ExternalGateway {
    private val results = ConcurrentHashMap<String, String>()
    override suspend fun charge(key: String, amountMinor: Long): String {
        delay(50)
        // Внешний сервис тоже принимает idempotency key: retry не создаст второй charge.
        return results.computeIfAbsent(key) { "charge-$it" }
    }
}

class PaymentCoordinator(private val repository: PaymentRepository, private val gateway: ExternalGateway) {
    private val operationLocks = ConcurrentHashMap<String, Mutex>()

    suspend fun pay(key: String, amountMinor: Long): Payment {
        require(key.isNotBlank()) { "idempotency key must not be blank" }
        require(amountMinor > 0) { "amountMinor must be positive" }

        // Один coordinator не запускает один operation key параллельно. В нескольких
        // instances эту роль должны выполнять UNIQUE constraint и state transition в БД.
        return operationLocks.computeIfAbsent(key) { Mutex() }.withLock {
            val existing = repository.find(key)
            require(existing == null || existing.amountMinor == amountMinor) {
                "idempotency key was already used for another request"
            }
            existing?.takeIf { it.status == Status.COMPLETED }?.let { return@withLock it }

            // В production эти два save — короткие локальные DB transactions.
            repository.save(Payment(key, amountMinor, Status.RESERVED))
            try {
                // Сетевой вызов ограничен timeout и выполняется без открытой DB transaction.
                withTimeout(500) { gateway.charge(key, amountMinor) }
                repository.save(Payment(key, amountMinor, Status.COMPLETED))
            } catch (cancelled: CancellationException) {
                // Отмена не доказывает, что внешний charge не состоялся. RESERVED должен
                // быть согласован повторным idempotent lookup/reconciliation.
                throw cancelled
            } catch (error: Exception) {
                repository.save(Payment(key, amountMinor, Status.FAILED))
                throw error
            }
        }
    }
}

fun main() = runBlocking { println(PaymentCoordinator(MemoryRepository(), DemoGateway()).pay("demo", 100)) }
