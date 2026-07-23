package study.week3copy

import java.util.UUID

data class AccountSnapshot(
    val accountId: UUID,
    val storedBalanceMinor: Long,
    val ledgerBalanceMinor: Long,
    val paymentCount: Long,
)
