// Проверяет идемпотентность и переходы состояний координатора платежей.
// Тест относится к учебному модулю недели 11 и фиксирует ожидаемое поведение кода.
package study.week11

import org.junit.jupiter.api.TestInstance

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class PaymentCoordinatorTest {
    @Test
    // Проверяет возврат одного завершённого платежа при повторе запроса.
    fun `retry returns one completed payment`() = runTest {
        var calls = 0
        val gateway = object : ExternalGateway {
            // Регистрирует тестовый вызов шлюза и возвращает успешный результат.
            override suspend fun charge(key: String, amountMinor: Long): String { calls++; return "ok" }
        }
        val coordinator = PaymentCoordinator(MemoryRepository(), gateway)
        coordinator.pay("same", 100)
        coordinator.pay("same", 100)
        assertEquals(1, calls)
    }

    @Test
    // Проверяет единственный вызов шлюза при конкурентных повторах.
    fun `concurrent retries call gateway once`() = runTest {
        var calls = 0
        val gateway = object : ExternalGateway {
            // Имитирует задержанный вызов шлюза и учитывает число обращений.
            override suspend fun charge(key: String, amountMinor: Long): String {
                calls++
                delay(10)
                return "ok"
            }
        }
        val coordinator = PaymentCoordinator(MemoryRepository(), gateway)

        val payments = coroutineScope {
            List(10) { async { coordinator.pay("same", 100) } }.awaitAll()
        }

        assertEquals(1, calls)
        assertEquals(setOf(Status.COMPLETED), payments.map { it.status }.toSet())
    }

    @Test
    // Проверяет запрет изменения суммы для прежнего ключа идемпотентности.
    fun `same key cannot change amount`() = runTest {
        val coordinator = PaymentCoordinator(MemoryRepository(), DemoGateway())
        coordinator.pay("same", 100)

        assertFailsWith<IllegalArgumentException> { coordinator.pay("same", 200) }
    }

    @Test
    // Проверяет сохранение зарезервированной операции после отмены.
    fun `cancellation leaves operation reserved for reconciliation`() = runTest {
        val repository = MemoryRepository()
        val gateway = object : ExternalGateway {
            // Имитирует внешний вызов, который завершается только отменой.
            override suspend fun charge(key: String, amountMinor: Long): String = awaitCancellation()
        }
        val coordinator = PaymentCoordinator(repository, gateway)

        assertFailsWith<TimeoutCancellationException> {
            withTimeout(10) { coordinator.pay("uncertain", 100) }
        }
        assertEquals(Status.RESERVED, repository.find("uncertain")?.status)
    }
}
