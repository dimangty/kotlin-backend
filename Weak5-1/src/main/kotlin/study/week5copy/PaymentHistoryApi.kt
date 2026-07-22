package study.week5copy

import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

data class GeneratePaymentsRequest(
    @field:Min(1) @field:Max(100_000) val count: Int,
)

data class PaymentView(
    val id: Long,
    val userId: Long,
    val reference: String,
    val status: String,
    val amountMinor: Long,
    val createdAt: Instant,
)

data class IndexDescription(val name: String, val definition: String)

@Service
class PaymentHistoryService(private val jdbc: JdbcTemplate) {
    fun generate(request: GeneratePaymentsRequest): Int {
        val inserted = jdbc.update(
            """
            INSERT INTO payments(user_id, reference, status, amount_minor, created_at, metadata)
            SELECT 1 + (random() * 999)::bigint,
                   'PAY-' || gen_random_uuid(),
                   CASE WHEN random() < .02 THEN 'PENDING' ELSE 'COMPLETED' END,
                   100 + (random() * 100000)::bigint,
                   now() - random() * interval '730 days',
                   jsonb_build_object('channel', CASE WHEN n % 2 = 0 THEN 'MOBILE' ELSE 'WEB' END)
            FROM generate_series(1, ?) AS n
            """.trimIndent(),
            request.count,
        )

        // ANALYZE обновляет cardinality/selectivity, а VACUUM помечает полностью видимые
        // страницы в visibility map — это необходимое условие настоящего Index Only Scan.
        jdbc.execute("VACUUM (ANALYZE) payments")
        return inserted
    }

    fun history(userId: Long, from: Instant, limit: Int): List<PaymentView> {
        require(limit in 1..100) { "limit must be between 1 and 100" }
        return jdbc.query(
            """
            SELECT id, user_id, reference, status, amount_minor, created_at
            FROM payments
            WHERE user_id = ? AND created_at >= ?
            ORDER BY created_at DESC
            LIMIT ?
            """.trimIndent(),
            paymentRowMapper,
            userId,
            java.sql.Timestamp.from(from),
            limit,
        )
    }

    fun pendingBefore(before: Instant, limit: Int): List<PaymentView> {
        require(limit in 1..100) { "limit must be between 1 and 100" }
        return jdbc.query(
            // Явный status='PENDING' совпадает с predicate partial index. Если условие
            // убрать или параметризовать произвольным статусом, planner не обязан его применять.
            """
            SELECT id, user_id, reference, status, amount_minor, created_at
            FROM payments
            WHERE status = 'PENDING' AND created_at < ?
            ORDER BY created_at
            LIMIT ?
            """.trimIndent(),
            paymentRowMapper,
            java.sql.Timestamp.from(before),
            limit,
        )
    }

    fun explainHistory(userId: Long, from: Instant): String = jdbc.queryForObject(
        // estimated/actual rows и buffers позволяют проверить решение численно,
        // а не считать любой созданный индекс автоматически полезным.
        """
        EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)
        SELECT id, user_id, reference, status, amount_minor, created_at
        FROM payments
        WHERE user_id = ? AND created_at >= ?
        ORDER BY created_at DESC
        LIMIT 50
        """.trimIndent(),
        String::class.java,
        userId,
        java.sql.Timestamp.from(from),
    )!!

    fun indexes(): List<IndexDescription> = jdbc.query(
        """
        SELECT indexname, indexdef
        FROM pg_indexes
        WHERE schemaname = 'public' AND tablename = 'payments'
        ORDER BY indexname
        """.trimIndent(),
    ) { rs, _ -> IndexDescription(rs.getString("indexname"), rs.getString("indexdef")) }

    private val paymentRowMapper = org.springframework.jdbc.core.RowMapper<PaymentView> { rs, _ ->
        PaymentView(
            id = rs.getLong("id"),
            userId = rs.getLong("user_id"),
            reference = rs.getString("reference"),
            status = rs.getString("status"),
            amountMinor = rs.getLong("amount_minor"),
            createdAt = rs.getTimestamp("created_at").toInstant(),
        )
    }
}

@RestController
@RequestMapping("/api/payments")
class PaymentHistoryController(private val service: PaymentHistoryService) {
    @PostMapping("/generate")
    fun generate(@Valid @RequestBody request: GeneratePaymentsRequest) = mapOf("inserted" to service.generate(request))

    @GetMapping("/history")
    fun history(
        @RequestParam userId: Long,
        @RequestParam from: Instant,
        @RequestParam(defaultValue = "50") limit: Int,
    ) = service.history(userId, from, limit)

    @GetMapping("/pending")
    fun pending(
        @RequestParam before: Instant,
        @RequestParam(defaultValue = "50") limit: Int,
    ) = service.pendingBefore(before, limit)

    @GetMapping("/history/plan")
    fun historyPlan(@RequestParam userId: Long, @RequestParam from: Instant) = service.explainHistory(userId, from)

    @GetMapping("/indexes")
    fun indexes() = service.indexes()
}
