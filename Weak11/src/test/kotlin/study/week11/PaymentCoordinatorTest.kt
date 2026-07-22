package study.week11

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

class PaymentCoordinatorTest {
    @Test
    fun `retry returns one completed payment`() = runTest {
        var calls = 0
        val gateway = object : ExternalGateway {
            override suspend fun charge(key: String, amountMinor: Long): String { calls++; return "ok" }
        }
        val coordinator = PaymentCoordinator(MemoryRepository(), gateway)
        coordinator.pay("same", 100)
        coordinator.pay("same", 100)
        assertEquals(1, calls)
    }

    @Test
    fun `concurrent retries call gateway once`() = runTest {
        var calls = 0
        val gateway = object : ExternalGateway {
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
    fun `same key cannot change amount`() = runTest {
        val coordinator = PaymentCoordinator(MemoryRepository(), DemoGateway())
        coordinator.pay("same", 100)

        assertFailsWith<IllegalArgumentException> { coordinator.pay("same", 200) }
    }

    @Test
    fun `cancellation leaves operation reserved for reconciliation`() = runTest {
        val repository = MemoryRepository()
        val gateway = object : ExternalGateway {
            override suspend fun charge(key: String, amountMinor: Long): String = awaitCancellation()
        }
        val coordinator = PaymentCoordinator(repository, gateway)

        assertFailsWith<TimeoutCancellationException> {
            withTimeout(10) { coordinator.pay("uncertain", 100) }
        }
        assertEquals(Status.RESERVED, repository.find("uncertain")?.status)
    }
}
