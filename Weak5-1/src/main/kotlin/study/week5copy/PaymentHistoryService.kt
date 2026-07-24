package study.week5copy

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.time.Instant

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

    fun explainHistory(userId: Long, from: Instant): String = requireNotNull(
        jdbc.queryForObject(
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
        ),
    ) { "EXPLAIN must return a JSON plan" }

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
