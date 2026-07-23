package study.week4copy

import java.time.Instant
import java.util.UUID

data class EventView(
    val id: Long,
    val publicId: UUID,
    val userId: Long,
    val status: String,
    val createdAt: Instant,
)
