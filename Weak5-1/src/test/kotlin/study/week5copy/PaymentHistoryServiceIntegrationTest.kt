// Проверяет планы запросов и свойства индексов на реальной базе.
// Тест относится к учебному модулю недели 5 и фиксирует ожидаемое поведение кода.
package study.week5copy

import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.springframework.test.annotation.DirtiesContext

import org.junit.jupiter.api.TestInstance

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
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

@SpringBootTest
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
@Execution(ExecutionMode.SAME_THREAD)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
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
        // Передаёт приложению параметры запущенной тестовой базы данных.
        fun database(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }

    @BeforeEach
    // Очищает платёжные данные перед каждым тестом.
    fun reset() {
        jdbc.execute("TRUNCATE payments RESTART IDENTITY")
    }

    @AfterEach
    // Останавливает контейнер базы после завершения тестов.
    fun cleanup() {
        jdbc.execute("TRUNCATE payments RESTART IDENTITY")
    }

    @Test
    // Проверяет ограничение и обратную хронологическую сортировку истории.
    fun `user history is bounded and sorted newest first`() {
        assertEquals(10_000, service.generate(GeneratePaymentsRequest(10_000)))
        val from = Instant.now().minusSeconds(730L * 24 * 60 * 60)

        val history = service.history(userId = 42, from = from, limit = 50)
        assertTrue(history.isNotEmpty())
        assertTrue(history.size <= 50)
        assertTrue(history.zipWithNext().all { (left, right) -> left.createdAt >= right.createdAt })
    }

    @Test
    // Проверяет обслуживание запроса истории покрывающим индексом.
    fun `covering index serves user history query`() {
        assertEquals(10_000, service.generate(GeneratePaymentsRequest(10_000)))
        val from = Instant.now().minusSeconds(730L * 24 * 60 * 60)
        val plan = service.explainHistory(userId = 42, from = from)
        assertTrue(plan.contains("Index Only Scan"), plan)
        assertTrue(plan.contains("payments_user_created_cover_idx"), plan)
    }

    @Test
    // Проверяет создание покрывающего индекса миграцией.
    fun `migration creates a covering index`() {
        assertTrue(indexDefinition("payments_user_created_cover_idx").contains("INCLUDE"))
    }

    @Test
    // Проверяет создание частичного индекса миграцией.
    fun `migration creates a partial index`() {
        assertTrue(indexDefinition("payments_pending_idx").contains("WHERE (status = 'PENDING'"))
    }

    @Test
    // Проверяет создание функционального индекса миграцией.
    fun `migration creates an expression index`() {
        assertTrue(indexDefinition("payments_reference_lower_idx").contains("lower(reference)"))
    }

    @Test
    // Проверяет создание GIN-индекса миграцией.
    fun `migration creates a gin index`() {
        assertTrue(indexDefinition("payments_metadata_gin_idx").contains("USING gin"))
    }

    @Test
    // Проверяет создание BRIN-индекса миграцией.
    fun `migration creates a brin index`() {
        assertTrue(indexDefinition("payments_created_brin_idx").contains("USING brin"))
    }

    // Возвращает SQL-описание индекса по его имени.
    private fun indexDefinition(name: String): String =
        service.indexes().associateBy { it.name }.getValue(name).definition
}
