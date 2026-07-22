package study.week6copy

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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@SpringBootTest
@Testcontainers
class AccountServiceIntegrationTest @Autowired constructor(
    private val accounts: AccountService,
    private val serializableDebits: SerializableDebitService,
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
        jdbc.execute("TRUNCATE accounts")
    }

    @Test
    fun `atomic update prevents lost updates under contention`() {
        val account = accounts.create(CreateAccountRequest(1_000))
        val results = runConcurrently(10) { accounts.atomicDebit(account.id, 100) }

        assertEquals(10, results.size)
        assertEquals(0, accounts.balance(account.id).balanceMinor)
    }

    @Test
    fun `row lock makes competing decisions observe latest committed balance`() {
        val account = accounts.create(CreateAccountRequest(1_000))
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val futures = (1..2).map {
                pool.submit<Boolean> {
                    start.await()
                    runCatching { accounts.lockedDebit(account.id, 600) }.isSuccess
                }
            }
            start.countDown()

            assertEquals(1, futures.count { it.get(15, TimeUnit.SECONDS) })
            assertEquals(400, accounts.balance(account.id).balanceMinor)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `serializable retry repeats the whole debit transaction`() {
        val account = accounts.create(CreateAccountRequest(1_000))
        val results = runConcurrently(5) { serializableDebits.debit(account.id, 100, maxAttempts = 50) }

        assertEquals(500, accounts.balance(account.id).balanceMinor)
        // Одновременные read-compute-write транзакции конфликтуют; хотя бы одна должна
        // получить SQLSTATE 40001 и успешно повториться с новым snapshot.
        assertTrue(results.any { it.attempts > 1 }, results.toString())
    }

    private fun runConcurrently(count: Int, action: () -> AccountView): List<AccountView> {
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(count)
        return try {
            val futures = (1..count).map {
                pool.submit<AccountView> {
                    start.await()
                    action()
                }
            }
            start.countDown()
            futures.map { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
    }
}
