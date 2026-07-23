package study.week3copy

import java.util.UUID

data class PaymentView(val id: UUID, val accountId: UUID, val amountMinor: Long, val status: String)
