package study.week16

import org.springframework.web.bind.annotation.*
import java.util.UUID

data class LedgerEntryView(val id: Long, val transferId: UUID, val amountMinor: Long)
