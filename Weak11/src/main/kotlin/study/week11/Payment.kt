// Описывает платёж и его текущее состояние.
// Компонент относится к учебному модулю недели 11 и раскрывает его основной пример.
package study.week11


data class Payment(val idempotencyKey: String, val amountMinor: Long, val status: Status)
