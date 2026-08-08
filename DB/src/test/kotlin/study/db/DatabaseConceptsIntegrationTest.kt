package study.db

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@SpringBootTest
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
@Execution(ExecutionMode.SAME_THREAD)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DatabaseConceptsIntegrationTest @Autowired constructor(
    private val accounts: AccountService,
    private val isolation: IsolationService,
    private val locks: LockService,
    private val indexes: IndexService,
    private val jdbc: JdbcTemplate,
) {
    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:17-alpine")

        @DynamicPropertySource
        @JvmStatic
        // Spring подключается к настоящему PostgreSQL из Testcontainers, а не к упрощённой H2.
        fun database(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }

    private val firstId = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val secondId = UUID.fromString("00000000-0000-0000-0000-000000000002")

    @BeforeEach
    fun reset() {
        jdbc.execute("TRUNCATE ledger_entries, transfers, jobs, payments, accounts RESTART IDENTITY CASCADE")
        jdbc.update(
            "INSERT INTO accounts(id, owner_name, balance_minor) VALUES (?, 'Первый', 1000), (?, 'Второй', 1000)",
            firstId,
            secondId,
        )
    }

    @AfterEach
    fun cleanup() {
        jdbc.execute("TRUNCATE ledger_entries, transfers, jobs, payments, accounts RESTART IDENTITY CASCADE")
    }

    @Test
    // Atomicity: искусственная ошибка откатывает даже уже выполненное списание.
    fun `failed transfer rolls back every change`() {
        assertThrows<IllegalStateException> {
            accounts.transfer(TransferRequest(firstId, secondId, 100, failAfterDebit = true))
        }

        assertEquals(1000, accounts.get(firstId).balanceMinor)
        assertEquals(1000, accounts.get(secondId).balanceMinor)
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM transfers", Int::class.java))
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM ledger_entries", Int::class.java))
    }

    @Test
    // Atomic conditional UPDATE не допускает перерасход при конкурентных списаниях.
    fun `atomic debit preserves non-negative balance`() {
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(10)
        try {
            val futures = (1..10).map {
                pool.submit {
                    start.await()
                    accounts.atomicDebit(firstId, 100)
                }
            }
            start.countDown()
            futures.forEach { it.get(20, TimeUnit.SECONDS) }
            assertEquals(0, accounts.get(firstId).balanceMinor)
        } finally {
            pool.shutdownNow()
            assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS))
        }
    }

    @Test
    // Serializable заставляет конфликтующие read-compute-write операции повториться безопасно.
    fun `serializable retry does not lose an update`() {
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val futures = (1..2).map {
                pool.submit {
                    start.await()
                    isolation.serializableChange(BalanceChangeRequest(firstId, 100, pauseMillis = 100))
                }
            }
            start.countDown()
            futures.forEach { it.get(20, TimeUnit.SECONDS) }
            assertEquals(1200, accounts.get(firstId).balanceMinor)
        } finally {
            pool.shutdownNow()
            assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS))
        }
    }

    @Test
    // Единый порядок FOR UPDATE сохраняет сумму при встречных переводах.
    fun `ordered row locks prevent deadlock in opposing transfers`() {
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(8)
        try {
            val futures = (1..20).map { number ->
                pool.submit {
                    start.await()
                    val request = if (number % 2 == 0) {
                        TransferRequest(firstId, secondId, 10)
                    } else {
                        TransferRequest(secondId, firstId, 10)
                    }
                    locks.lockedTransfer(request)
                }
            }
            start.countDown()
            futures.forEach { it.get(20, TimeUnit.SECONDS) }

            assertEquals(2000, accounts.get(firstId).balanceMinor + accounts.get(secondId).balanceMinor)
            assertEquals(20, jdbc.queryForObject("SELECT count(*) FROM transfers", Int::class.java))
            assertEquals(0, jdbc.queryForObject("SELECT sum(amount_minor) FROM ledger_entries", Long::class.java))
        } finally {
            pool.shutdownNow()
            assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS))
        }
    }

    @Test
    // Миграция действительно создаёт все показанные типы учебных индексов.
    fun `migration creates educational indexes`() {
        val definitions = indexes.indexes().joinToString("\n") { it.definition.lowercase() }

        assertTrue(definitions.contains("include"))
        assertTrue(definitions.contains("where (status = 'pending'"))
        assertTrue(definitions.contains("lower(reference)"))
        assertTrue(definitions.contains("using gin"))
        assertTrue(definitions.contains("using brin"))
    }
}
