package study.week8

import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
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

interface PaymentRepository : JpaRepository<Payment, UUID>

data class DailyTotal(val day: String, val totalMinor: Long)

@Service
class PaymentService(private val jpa: PaymentRepository, private val jdbc: JdbcTemplate) {
    @Transactional
    fun complete(id: UUID) {
        val payment = jpa.findById(id).orElseThrow()
        // Dirty checking создаст UPDATE при commit; transaction boundary находится в public service method.
        payment.status = "COMPLETED"
    }

    fun dailyTotals(accountId: UUID): List<DailyTotal> = jdbc.query(
        """
        SELECT created_at::date AS day, sum(amount_minor) AS total
        FROM payments WHERE account_id = ? AND status = 'COMPLETED'
        GROUP BY created_at::date ORDER BY day DESC
        """.trimIndent(),
        { rs, _ -> DailyTotal(rs.getString("day"), rs.getLong("total")) }, accountId,
    )
}

