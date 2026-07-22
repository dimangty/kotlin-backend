package study.week15

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TransferServiceIntegrationTest {
    companion object {
        private val fromId = UUID.fromString("00000000-0000-0000-0000-000000000001")
        private val toId = UUID.fromString("00000000-0000-0000-0000-000000000002")

        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:17-alpine")
    }

    private val dataSource: HikariDataSource by lazy {
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = postgres.jdbcUrl
                username = postgres.username
                password = postgres.password
                maximumPoolSize = 4
            },
        )
    }
    private val service: TransferService by lazy { TransferService(dataSource) }

    @BeforeEach
    fun reset() {
        Flyway.configure().dataSource(dataSource).load().migrate()
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("TRUNCATE ledger_entries, transfers, accounts")
            }
            connection.prepareStatement(
                "INSERT INTO accounts(id,balance_minor) VALUES (?,1000),(?,1000)",
            ).use { statement ->
                statement.setObject(1, fromId)
                statement.setObject(2, toId)
                statement.executeUpdate()
            }
        }
    }

    @AfterAll
    fun closePool() {
        dataSource.close()
    }

    @Test
    fun `concurrent retry creates one transfer and balanced ledger`() {
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val request = TransferRequest(fromId.toString(), toId.toString(), 100)
            val futures = (1..2).map {
                pool.submit<TransferResult> {
                    start.await()
                    service.transfer("same-key", request)
                }
            }
            start.countDown()
            val results = futures.map { it.get(15, TimeUnit.SECONDS) }

            assertEquals(1, results.map { it.id }.distinct().size)
            assertEquals(1, scalar("SELECT count(*) FROM transfers"))
            assertEquals(2, scalar("SELECT count(*) FROM ledger_entries"))
            assertEquals(0, scalar("SELECT sum(amount_minor) FROM ledger_entries"))
            assertEquals(2000, scalar("SELECT sum(balance_minor) FROM accounts"))
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `same key cannot change payload`() {
        service.transfer("same-key", TransferRequest(fromId.toString(), toId.toString(), 100))

        assertThrows<IllegalArgumentException> {
            service.transfer("same-key", TransferRequest(fromId.toString(), toId.toString(), 200))
        }
    }

    private fun scalar(sql: String): Long = dataSource.connection.use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { rows ->
                rows.next()
                rows.getLong(1)
            }
        }
    }
}
