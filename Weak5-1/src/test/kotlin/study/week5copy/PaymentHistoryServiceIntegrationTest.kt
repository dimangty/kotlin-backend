package study.week5copy

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
import java.time.Instant

@SpringBootTest
@Testcontainers
class PaymentHistoryServiceIntegrationTest @Autowired constructor(
    private val service: PaymentHistoryService,
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
        jdbc.execute("TRUNCATE payments RESTART IDENTITY")
    }

    @Test
    fun `covering index serves bounded user history`() {
        // Два вызова одновременно проверяют, что generator создаёт уникальные references.
        assertEquals(10_000, service.generate(GeneratePaymentsRequest(10_000)))
        assertEquals(10_000, service.generate(GeneratePaymentsRequest(10_000)))
        val from = Instant.now().minusSeconds(730L * 24 * 60 * 60)

        val history = service.history(userId = 42, from = from, limit = 50)
        assertTrue(history.isNotEmpty())
        assertTrue(history.zipWithNext().all { (left, right) -> left.createdAt >= right.createdAt })

        val plan = service.explainHistory(userId = 42, from = from)
        assertTrue(plan.contains("Index Only Scan"), plan)
        assertTrue(plan.contains("payments_user_created_cover_idx"), plan)
    }

    @Test
    fun `migration documents composite partial expression gin and brin indexes`() {
        val indexes = service.indexes().associateBy { it.name }

        assertTrue(indexes.getValue("payments_user_created_cover_idx").definition.contains("INCLUDE"))
        assertTrue(indexes.getValue("payments_pending_idx").definition.contains("WHERE (status = 'PENDING'"))
        assertTrue(indexes.getValue("payments_reference_lower_idx").definition.contains("lower(reference)"))
        assertTrue(indexes.getValue("payments_metadata_gin_idx").definition.contains("USING gin"))
        assertTrue(indexes.getValue("payments_created_brin_idx").definition.contains("USING brin"))
    }
}
