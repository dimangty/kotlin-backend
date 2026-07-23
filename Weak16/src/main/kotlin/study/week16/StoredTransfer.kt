package study.week16

import org.springframework.web.bind.annotation.*
import java.util.UUID

internal data class StoredTransfer(
    val view: TransferView,
    val fromAccountId: UUID,
    val toAccountId: UUID,
    val amountMinor: Long,
)
