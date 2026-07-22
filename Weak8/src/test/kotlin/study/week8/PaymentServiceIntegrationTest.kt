package study.week8

import org.junit.jupiter.api.Assertions.assertEquals
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
import java.util.UUID

@SpringBootTest
@Testcontainers
class PaymentServiceIntegrationTest @Autowired constructor(
    private val service: PaymentService,
    private val repository: PaymentRepository,
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
        jdbc.execute("TRUNCATE payments")
    }

    @Test
    fun `jpa transition and jdbc projection use the flyway schema`() {
        val accountId = UUID.randomUUID()
        val paymentId = UUID.randomUUID()
        repository.saveAndFlush(Payment(paymentId, accountId, 250, "PENDING", Instant.parse("2026-07-22T10:00:00Z")))

        service.complete(paymentId)

        assertEquals("COMPLETED", repository.findById(paymentId).orElseThrow().status)
        assertEquals(listOf(DailyTotal("2026-07-22", 250)), service.dailyTotals(accountId))
    }
}
