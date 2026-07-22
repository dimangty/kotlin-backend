package study.week11

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
    suspend fun pay(key: String, amountMinor: Long): Payment {
        repository.find(key)?.takeIf { it.status == Status.COMPLETED }?.let { return it }

        // В production эти два save — короткие локальные DB transactions.
        repository.save(Payment(key, amountMinor, Status.RESERVED))
        return try {
            // Сетевой вызов ограничен timeout и выполняется без открытой DB transaction.
            withTimeout(500) { gateway.charge(key, amountMinor) }
            repository.save(Payment(key, amountMinor, Status.COMPLETED))
        } catch (error: Exception) {
            repository.save(Payment(key, amountMinor, Status.FAILED))
            throw error
        }
    }
}

fun main() = runBlocking { println(PaymentCoordinator(MemoryRepository(), DemoGateway()).pay("demo", 100)) }

