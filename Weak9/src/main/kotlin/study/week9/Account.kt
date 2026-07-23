package study.week9

import org.springframework.web.bind.annotation.*
import java.util.UUID

data class Account(val id: UUID, val ownerId: UUID, val balanceMinor: Long)
