// Описывает запрос на создание платежа.
// Компонент относится к учебному модулю недели 3 и раскрывает его основной пример.
package study.week3copy

import jakarta.validation.constraints.Positive
import java.util.UUID

data class CreatePaymentRequest(
    val accountId: UUID,
    @field:Positive val amountMinor: Long,
    val status: PaymentStatus,
)
