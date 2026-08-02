// Проверяет сохранение платежей и расчёт дневных агрегатов.
// Тест относится к учебному модулю недели 8 и фиксирует ожидаемое поведение кода.
package study.week8

import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.springframework.test.annotation.DirtiesContext

import org.junit.jupiter.api.TestInstance

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.AfterEach
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
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
@Execution(ExecutionMode.SAME_THREAD)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
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
        // Передаёт приложению параметры запущенной тестовой базы данных.
        fun database(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }

    @BeforeEach
    // Очищает платежи перед каждым тестовым сценарием.
    fun reset() {
        jdbc.execute("TRUNCATE payments")
    }

    @AfterEach
    // Останавливает контейнер базы после завершения тестов.
    fun cleanup() {
        jdbc.execute("TRUNCATE payments")
    }

    @Test
    // Проверяет завершение ожидающего платежа через JPA.
    fun `jpa transition completes a pending payment`() {
        val accountId = UUID.randomUUID()
        val paymentId = UUID.randomUUID()
        repository.saveAndFlush(Payment(paymentId, accountId, 250, "PENDING", Instant.parse("2026-07-22T10:00:00Z")))

        service.complete(paymentId)

        assertEquals("COMPLETED", repository.findById(paymentId).orElseThrow().status)
    }

    @Test
    // Проверяет дневную агрегацию завершённых платежей через JDBC.
    fun `jdbc projection aggregates completed payments by day`() {
        val accountId = UUID.randomUUID()
        val paymentId = UUID.randomUUID()
        repository.saveAndFlush(Payment(paymentId, accountId, 250, "COMPLETED", Instant.parse("2026-07-22T10:00:00Z")))

        assertEquals(listOf(DailyTotal("2026-07-22", 250)), service.dailyTotals(accountId))
    }
}
