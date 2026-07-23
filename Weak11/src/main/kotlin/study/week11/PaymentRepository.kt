package study.week11


interface PaymentRepository {
    fun find(key: String): Payment?
    fun save(payment: Payment): Payment
}
