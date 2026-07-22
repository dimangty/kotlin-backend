package study.week4copy

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.util.UUID

@SpringBootTest
@Testcontainers
class IndexLabServiceIntegrationTest @Autowired constructor(
    private val service: IndexLabService,
    private val jdbc: JdbcTemplate,
) {
    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:17-alpine")

        @DynamicPropertySource
        @JvmStatic
        fun database(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }

    @BeforeEach
    fun reset() {
        jdbc.execute("TRUNCATE events RESTART IDENTITY")
    }

    @Test
    fun `uuid lookup uses btree and lab reports relation sizes`() {
        assertEquals(5_000, service.generate(GenerateEventsRequest(5_000)))
        val publicId = jdbc.queryForObject("SELECT public_id FROM events WHERE id = 2500", UUID::class.java)!!

        assertEquals(publicId, service.findByPublicId(publicId).publicId)

        val plan = service.explainUuidLookup(publicId)
        assertTrue(plan.contains("Index Scan"), plan)
        assertTrue(plan.contains("events_public_id_key"), plan)

        val sizes = service.sizes()
        assertTrue(sizes.heapBytes > 0)
        assertTrue(sizes.indexesBytes > 0)
        assertEquals(5_000, service.statusDistribution().sumOf { it.count })
    }

    @Test
    fun `migration creates indexes for contrasting selectivity`() {
        val definitions = jdbc.queryForList(
            "SELECT indexdef FROM pg_indexes WHERE schemaname = 'public' AND tablename = 'events'",
            String::class.java,
        ).joinToString("\n")

        assertTrue(definitions.contains("events_public_id_key"))
        assertTrue(definitions.contains("events_created_at_idx"))
        assertTrue(definitions.contains("events_status_idx"))
    }
}
