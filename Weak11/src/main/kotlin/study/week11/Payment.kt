package study.week11


data class Payment(val idempotencyKey: String, val amountMinor: Long, val status: Status)
