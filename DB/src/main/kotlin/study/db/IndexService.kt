package study.db

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.sql.Timestamp
import java.time.Instant

@Service
class IndexService(private val jdbc: JdbcTemplate) {
    // Генерирует данные с редким PENDING, чтобы partial index имел смысл.
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
        // Статистика нужна planner-у для оценки cardinality и выбора плана.
        jdbc.execute("ANALYZE payments")
        return inserted
    }

    // Запрос соответствует порядку ключей индекса: equality, затем range и sort.
    fun history(userId: Long, from: Instant, limit: Int): List<PaymentView> {
        require(limit in 1..100) { "limit должен быть от 1 до 100" }
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
            Timestamp.from(from),
            limit,
        )
    }

    // EXPLAIN ANALYZE действительно выполняет SELECT и добавляет фактические строки/buffers.
    fun explainHistory(userId: Long, from: Instant): String = requireNotNull(
        jdbc.queryForObject(
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
            Timestamp.from(from),
        ),
    )

    // Каталог PostgreSQL показывает физически созданные индексы, а не описание из кода.
    fun indexes(): List<IndexView> = jdbc.query(
        """
        SELECT indexname, indexdef
        FROM pg_indexes
        WHERE schemaname = 'public' AND tablename = 'payments'
        ORDER BY indexname
        """.trimIndent(),
    ) { rs, _ -> IndexView(rs.getString("indexname"), rs.getString("indexdef")) }

    private val paymentRowMapper = org.springframework.jdbc.core.RowMapper<PaymentView> { rs, _ ->
        PaymentView(
            rs.getLong("id"),
            rs.getLong("user_id"),
            rs.getString("reference"),
            rs.getString("status"),
            rs.getLong("amount_minor"),
            rs.getTimestamp("created_at").toInstant(),
        )
    }
}
