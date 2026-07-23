package study.week15

import java.util.UUID

internal data class StoredTransfer(
    val result: TransferResult,
    val fromId: UUID,
    val toId: UUID,
    val amountMinor: Long,
)
