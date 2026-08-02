// Интеграционно проверяет конкурентные списания и целостность баланса.
// Тест относится к учебному модулю недели 6 и фиксирует ожидаемое поведение кода.
package study.week6copy

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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@SpringBootTest
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
@Execution(ExecutionMode.SAME_THREAD)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
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
        // Передаёт приложению параметры запущенной тестовой базы данных.
        fun database(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }

    @BeforeEach
    // Подготавливает таблицу счетов перед каждым тестом.
    fun reset() {
        jdbc.execute("TRUNCATE accounts")
    }

    @AfterEach
    // Останавливает контейнер базы после завершения тестов.
    fun cleanup() {
        jdbc.execute("TRUNCATE accounts")
    }

    @Test
    // Проверяет защиту атомарного обновления от потерянных изменений.
    fun `atomic update prevents lost updates under contention`() {
        val account = accounts.create(CreateAccountRequest(1_000))
        val results = runConcurrently(10) { accounts.atomicDebit(account.id, 100) }

        assertEquals(10, results.size)
        assertEquals(0, accounts.balance(account.id).balanceMinor)
    }

    @Test
    // Проверяет чтение актуального баланса конкурирующими транзакциями с блокировкой.
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
            shutdown(pool)
        }
    }

    @Test
    // Проверяет повтор всей транзакции после конфликта сериализации.
    fun `serializable retry repeats the whole debit transaction`() {
        val account = accounts.create(CreateAccountRequest(1_000))
        val results = runConcurrently(5) { serializableDebits.debit(account.id, 100, maxAttempts = 50) }

        assertEquals(500, accounts.balance(account.id).balanceMinor)
        // Одновременные read-compute-write транзакции конфликтуют; хотя бы одна должна
        // получить SQLSTATE 40001 и успешно повториться с новым snapshot.
        assertTrue(results.any { it.attempts > 1 }, results.toString())
    }

    // Одновременно запускает заданное число операций и собирает результаты.
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
            shutdown(pool)
        }
    }

    // Корректно завершает пул потоков теста.
    private fun shutdown(pool: ExecutorService) {
        pool.shutdownNow()
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "Рабочие потоки теста не завершились")
    }
}
