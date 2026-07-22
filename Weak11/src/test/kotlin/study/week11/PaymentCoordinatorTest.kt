package study.week11

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

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
}

