package study.week3copy

import jakarta.validation.constraints.Positive
import java.util.UUID

data class CreatePaymentRequest(
    val accountId: UUID,
    @field:Positive val amountMinor: Long,
    val status: PaymentStatus,
)
