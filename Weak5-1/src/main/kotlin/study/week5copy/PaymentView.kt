package study.week5copy

import java.time.Instant

data class PaymentView(
    val id: Long,
    val userId: Long,
    val reference: String,
    val status: String,
    val amountMinor: Long,
    val createdAt: Instant,
)
