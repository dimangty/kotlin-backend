package study.week3copy

import java.time.Instant
import java.util.UUID

data class UserView(val id: UUID, val email: String, val createdAt: Instant)
