package study.week16

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
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
class FintechServiceIntegrationTest @Autowired constructor(
    private val service: FintechService,
    private val jdbc: JdbcTemplate,
) {
    companion object {
        private val fromOwnerId = UUID.fromString("00000000-0000-0000-0000-000000000010")
        private val toOwnerId = UUID.fromString("00000000-0000-0000-0000-000000000011")
        private val fromId = UUID.fromString("00000000-0000-0000-0000-000000000001")
        private val toId = UUID.fromString("00000000-0000-0000-0000-000000000002")

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
        jdbc.execute("TRUNCATE audit_events, ledger_entries, transfers, accounts")
        jdbc.update(
            """
            INSERT INTO accounts(id, owner_id, currency, balance_minor)
            VALUES (?, ?, 'RUB', 1000), (?, ?, 'RUB', 1000)
            """.trimIndent(),
            fromId,
            fromOwnerId,
            toId,
            toOwnerId,
        )
    }

    @Test
    fun `concurrent retries preserve every invariant`() {
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val command = CreateTransfer(fromId, toId, 100)
            val futures = (1..2).map {
                pool.submit<TransferView> {
                    start.await()
                    service.transfer("same-key", command, "test-user")
                }
            }
            start.countDown()

            val responses = futures.map { it.get(15, TimeUnit.SECONDS) }
            assertEquals(1, responses.map { it.id }.distinct().size)
            assertEquals(1, count("transfers"))
            assertEquals(2, count("ledger_entries"))
            assertEquals(1, count("audit_events"))
            assertEquals(0L, jdbc.queryForObject("SELECT sum(amount_minor) FROM ledger_entries", Long::class.java)!!)
            assertEquals(2000L, jdbc.queryForObject("SELECT sum(balance_minor) FROM accounts", Long::class.java)!!)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `same key cannot change the transfer payload`() {
        service.transfer("same-key", CreateTransfer(fromId, toId, 100), "test-user")

        assertThrows<IllegalArgumentException> {
            service.transfer("same-key", CreateTransfer(fromId, toId, 200), "test-user")
        }
    }

    private fun count(table: String): Int = jdbc.queryForObject("SELECT count(*) FROM $table", Int::class.java)!!
}
