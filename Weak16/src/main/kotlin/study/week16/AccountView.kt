package study.week16

import org.springframework.web.bind.annotation.*
import java.util.UUID

data class AccountView(val id: UUID, val ownerId: UUID, val currency: String, val balanceMinor: Long)
