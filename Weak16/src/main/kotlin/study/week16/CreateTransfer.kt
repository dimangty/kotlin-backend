package study.week16

import org.springframework.web.bind.annotation.*
import java.util.UUID

data class CreateTransfer(val fromAccountId: UUID, val toAccountId: UUID, val amountMinor: Long)
