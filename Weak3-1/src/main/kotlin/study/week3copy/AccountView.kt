package study.week3copy

import java.util.UUID

data class AccountView(val id: UUID, val ownerId: UUID, val currency: String, val balanceMinor: Long)
