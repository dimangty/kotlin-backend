package study.week16

import org.springframework.web.bind.annotation.*
import java.util.UUID

data class CreateAccount(val ownerId: UUID, val currency: String, val initialBalanceMinor: Long = 0)
