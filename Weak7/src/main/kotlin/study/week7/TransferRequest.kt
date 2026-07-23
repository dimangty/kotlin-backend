package study.week7

import org.springframework.web.bind.annotation.*
import java.util.UUID

data class TransferRequest(val fromAccountId: UUID, val toAccountId: UUID, val amountMinor: Long)
