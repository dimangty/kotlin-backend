// Проверяет инварианты схемы PostgreSQL на уровне базы данных.
// Тест относится к учебному модулю недели 10 и фиксирует ожидаемое поведение кода.
package study.week10

import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

import org.junit.jupiter.api.TestInstance

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.DriverManager
import java.sql.SQLException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
@Execution(ExecutionMode.SAME_THREAD)
class PostgresInvariantTest {
    companion object {
        @Container @JvmStatic val postgres = PostgreSQLContainer("postgres:17-alpine")
    }

    @BeforeEach
    // Создаёт схему и очищает данные перед каждым тестом.
    fun reset() {
        connection().use { c ->
        c.createStatement().execute("DROP TABLE IF EXISTS requests; DROP TABLE IF EXISTS accounts")
        c.createStatement().execute("CREATE TABLE accounts(id int PRIMARY KEY, balance bigint NOT NULL CHECK(balance >= 0)); INSERT INTO accounts VALUES (1,1000),(2,1000)")
        c.createStatement().execute("CREATE TABLE requests(key text PRIMARY KEY)")
        }
    }

    @AfterEach
    // Останавливает контейнер PostgreSQL после выполнения тестов.
    fun cleanup() {
        connection().use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("DROP TABLE IF EXISTS requests; DROP TABLE IF EXISTS accounts")
            }
        }
    }

    @Test
    // Проверяет запрет дублирования ключа идемпотентности на уровне базы.
    fun `database rejects duplicate idempotency key`() {
        connection().use { c -> c.createStatement().execute("INSERT INTO requests VALUES ('same')") }
        assertThrows<SQLException> { connection().use { c -> c.createStatement().execute("INSERT INTO requests VALUES ('same')") } }
    }

    @Test
    // Проверяет неотрицательность баланса при параллельных списаниях.
    fun `parallel atomic debits preserve non-negative balance`() {
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(20)
        try {
            val futures = (1..20).map {
                pool.submit<Boolean> {
                    start.await()
                    connection().use { c ->
                        c.prepareStatement("UPDATE accounts SET balance=balance-100 WHERE id=1 AND balance>=100").use {
                            it.executeUpdate() == 1
                        }
                    }
                }
            }
            start.countDown()
            assertEquals(10, futures.count { it.get(15, TimeUnit.SECONDS) })
            connection().use { c ->
                c.createStatement().executeQuery("SELECT balance FROM accounts WHERE id=1").use {
                    it.next()
                    assertEquals(0, it.getLong(1))
                }
            }
        } finally {
            shutdown(pool)
        }
    }

    // Корректно завершает пул потоков теста.
    private fun shutdown(pool: ExecutorService) {
        pool.shutdownNow()
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "Рабочие потоки теста не завершились")
    }

    // Открывает соединение с тестовой базой PostgreSQL.
    private fun connection() = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
}
