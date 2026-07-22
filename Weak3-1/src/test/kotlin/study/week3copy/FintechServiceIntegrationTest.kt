package study.week3copy

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
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
        fun database(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }

    @BeforeEach
    fun reset() {
        jdbc.execute("TRUNCATE idempotency_keys, ledger_entries, payments, accounts, users RESTART IDENTITY CASCADE")
    }

    @Test
    fun `schema protects invariants and supports relational report`() {
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

        // PostgreSQL сообщает физический tuple address в форме "(page,offset)".
        val tuple = service.physicalTuple(account.id)
        assertTrue(tuple.ctid.matches(Regex("\\(\\d+,\\d+\\)")))
        assertTrue(tuple.xmin > 0)

        // UNIQUE работает независимо от того, какой клиент или endpoint делает INSERT.
        assertThrows<DataIntegrityViolationException> {
            service.createUser(CreateUserRequest("student@example.test"))
        }
        assertThrows<DataIntegrityViolationException> {
            // Прямой SQL обходит Kotlin normalization, но expression UNIQUE всё равно защищает invariant.
            jdbc.update("INSERT INTO users(email) VALUES ('STUDENT@EXAMPLE.TEST')")
        }
    }
}
