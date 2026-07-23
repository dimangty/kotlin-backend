package study.week8

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

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
