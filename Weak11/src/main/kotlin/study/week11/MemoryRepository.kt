// Хранит платежи в памяти с потокобезопасным доступом.
// Компонент относится к учебному модулю недели 11 и раскрывает его основной пример.
package study.week11

import java.util.concurrent.ConcurrentHashMap

class MemoryRepository : PaymentRepository {
    private val values = ConcurrentHashMap<String, Payment>()
    // Ищет платёж в памяти по ключу идемпотентности.
    override fun find(key: String) = values[key]
    // Сохраняет текущее состояние платежа в памяти.
    override fun save(payment: Payment): Payment { values[payment.idempotencyKey] = payment; return payment }
}
