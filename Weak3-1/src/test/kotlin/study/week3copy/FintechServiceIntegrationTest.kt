// Интеграционно проверяет финансовые сценарии и ограничения базы данных.
// Тест относится к учебному модулю недели 3 и фиксирует ожидаемое поведение кода.
package study.week3copy

import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.springframework.test.annotation.DirtiesContext

import org.junit.jupiter.api.TestInstance

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer

@SpringBootTest
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
@Execution(ExecutionMode.SAME_THREAD)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class FintechServiceIntegrationTest @Autowired constructor(
    private val service: FintechService,
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
    // Подготавливает чистые таблицы перед каждым тестом.
    fun reset() {
        jdbc.execute("TRUNCATE idempotency_keys, ledger_entries, payments, accounts, users RESTART IDENTITY CASCADE")
    }

    @AfterEach
    // Останавливает тестовый контейнер после выполнения набора тестов.
    fun cleanup() {
        jdbc.execute("TRUNCATE idempotency_keys, ledger_entries, payments, accounts, users RESTART IDENTITY CASCADE")
    }

    @Test
    // Проверяет объединение сохранённого баланса и записей журнала в снимке счёта.
    fun `account snapshot combines stored and ledger balances`() {
        val user = service.createUser(CreateUserRequest("Student@Example.test"))
        val account = service.openAccount(OpenAccountRequest(user.id, "RUB"))
        val payment = service.createPayment(CreatePaymentRequest(account.id, 250, PaymentStatus.COMPLETED))
        jdbc.update(
            "INSERT INTO ledger_entries(account_id, payment_id, amount_minor) VALUES (?, ?, ?)",
            account.id,
            payment.id,
            250,
        )

        val snapshot = service.accountSnapshot(account.id)
        assertEquals(0, snapshot.storedBalanceMinor)
        assertEquals(250, snapshot.ledgerBalanceMinor)
        assertEquals(1, snapshot.paymentCount)
    }

    @Test
    // Проверяет возврат физической версии строки PostgreSQL.
    fun `physical tuple exposes PostgreSQL row version`() {
        val user = service.createUser(CreateUserRequest("tuple@example.test"))
        val account = service.openAccount(OpenAccountRequest(user.id, "RUB"))
        // PostgreSQL сообщает физический tuple address в форме "(page,offset)".
        val tuple = service.physicalTuple(account.id)
        assertTrue(tuple.ctid.matches(Regex("\\(\\d+,\\d+\\)")))
        assertTrue(tuple.xmin > 0)
    }

    @Test
    // Проверяет отклонение сервисом адресов, отличающихся только регистром.
    fun `service rejects email that differs only by case`() {
        service.createUser(CreateUserRequest("Student@Example.test"))
        // UNIQUE работает независимо от того, какой клиент или endpoint делает INSERT.
        assertThrows<DataIntegrityViolationException> {
            service.createUser(CreateUserRequest("student@example.test"))
        }
    }

    @Test
    // Проверяет защиту базы от регистронезависимых дубликатов email.
    fun `database rejects case-insensitive duplicate email`() {
        service.createUser(CreateUserRequest("Student@Example.test"))
        assertThrows<DataIntegrityViolationException> {
            // Прямой SQL обходит Kotlin normalization, но expression UNIQUE всё равно защищает invariant.
            jdbc.update("INSERT INTO users(email) VALUES ('STUDENT@EXAMPLE.TEST')")
        }
    }
}
