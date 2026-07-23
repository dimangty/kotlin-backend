package study.week8

import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "payments")
class Payment(
    @Id val id: UUID,
    val accountId: UUID,
    val amountMinor: Long,
    var status: String,
    val createdAt: Instant,
)
