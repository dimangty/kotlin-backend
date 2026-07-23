package study.week11

import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap

class DemoGateway : ExternalGateway {
    private val results = ConcurrentHashMap<String, String>()
    override suspend fun charge(key: String, amountMinor: Long): String {
        delay(50)
        // Внешний сервис тоже принимает idempotency key: retry не создаст второй charge.
        return results.computeIfAbsent(key) { "charge-$it" }
    }
}
