// Хранит платежи в памяти с потокобезопасным доступом.
// Компонент относится к учебному модулю недели 11 и раскрывает его основной пример.
package study.week11

import java.util.concurrent.ConcurrentHashMap

class MemoryRepository : PaymentRepository {
    private val values = ConcurrentHashMap<String, Payment>()
    override fun find(key: String) = values[key]
    override fun save(payment: Payment): Payment { values[payment.idempotencyKey] = payment; return payment }
}
