// Объявляет операции хранения и поиска платежей.
// Компонент относится к учебному модулю недели 11 и раскрывает его основной пример.
package study.week11


interface PaymentRepository {
    // Ищет платёж по ключу идемпотентности.
    fun find(key: String): Payment?
    // Сохраняет и возвращает состояние платежа.
    fun save(payment: Payment): Payment
}
