// Задаёт внешнее представление платежа.
// Компонент относится к учебному модулю недели 3 и раскрывает его основной пример.
package study.week3copy

import java.util.UUID

data class PaymentView(val id: UUID, val accountId: UUID, val amountMinor: Long, val status: String)
