// Объявляет операции хранения и поиска платежей.
// Компонент относится к учебному модулю недели 11 и раскрывает его основной пример.
package study.week11


interface PaymentRepository {
    fun find(key: String): Payment?
    fun save(payment: Payment): Payment
}
