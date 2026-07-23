package study.week4copy

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class IndexLabService(private val jdbc: JdbcTemplate) {
    fun generate(request: GenerateEventsRequest): Int {
        val inserted = jdbc.update(
            """
            INSERT INTO events(public_id, user_id, status, created_at)
            SELECT gen_random_uuid(),
                   1 + (random() * 99999)::bigint,
                   (ARRAY['NEW', 'DONE', 'FAILED'])[(1 + floor(random() * 3))::int],
                   now() - random() * interval '365 days'
            FROM generate_series(1, ?)
            """.trimIndent(),
            request.count,
        )

        // Planner использует статистику, а не читает таблицу заново при планировании.
        // После массовой загрузки обновляем статистику явно для воспроизводимой лаборатории.
        jdbc.execute("ANALYZE events")
        return inserted
    }

    fun findByPublicId(publicId: UUID): EventView = jdbc.queryForObject(
        """
        SELECT id, public_id, user_id, status, created_at
        FROM events
        WHERE public_id = ?
        """.trimIndent(),
        { rs, _ ->
            EventView(
                id = rs.getLong("id"),
                publicId = rs.getObject("public_id", UUID::class.java),
                userId = rs.getLong("user_id"),
                status = rs.getString("status"),
                createdAt = rs.getTimestamp("created_at").toInstant(),
            )
        },
        publicId,
    )!!

    fun explainUuidLookup(publicId: UUID): String = jdbc.queryForObject(
        // FORMAT JSON удобно отдавать клиенту без парсинга текстового дерева.
        // ANALYZE действительно выполняет SELECT, а BUFFERS показывает чтения cache/heap.
        """
        EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)
        SELECT id, public_id, user_id, status, created_at
        FROM events
        WHERE public_id = ?
        """.trimIndent(),
        String::class.java,
        publicId,
    )!!

    fun statusDistribution(): List<StatusCount> = jdbc.query(
        "SELECT status, count(*) AS total FROM events GROUP BY status ORDER BY status",
    ) { rs, _ -> StatusCount(rs.getString("status"), rs.getLong("total")) }

    fun sizes(): RelationSizes = jdbc.queryForObject(
        """
        SELECT pg_relation_size('events') AS heap_bytes,
               pg_indexes_size('events') AS indexes_bytes
        """.trimIndent(),
    ) { rs, _ -> RelationSizes(rs.getLong("heap_bytes"), rs.getLong("indexes_bytes")) }!!
}
