package study.week7

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
class TransferServiceIntegrationTest @Autowired constructor(
    private val service: TransferService,
    private val jdbc: JdbcTemplate,
) {
    companion object {
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
        jdbc.execute("TRUNCATE ledger_entries, transfers, accounts")
        jdbc.update(
            "INSERT INTO accounts(id, balance_minor) VALUES (?, 1000), (?, 1000)",
            fromId,
            toId,
        )
    }

    @Test
    fun `concurrent retries create one transfer`() {
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val command = TransferRequest(fromId, toId, 100)
            val futures = (1..2).map {
                pool.submit<TransferResponse> {
                    start.await()
                    service.transfer("same-key", command)
                }
            }
            start.countDown()

            val responses = futures.map { it.get(15, TimeUnit.SECONDS) }
            assertEquals(1, responses.map { it.id }.distinct().size)
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM transfers", Int::class.java)!!)
            assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM ledger_entries", Int::class.java)!!)
            assertEquals(900, balance(fromId))
            assertEquals(1100, balance(toId))
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `same key cannot be reused for another request`() {
        service.transfer("same-key", TransferRequest(fromId, toId, 100))

        assertThrows<IllegalArgumentException> {
            service.transfer("same-key", TransferRequest(fromId, toId, 200))
        }
    }

    private fun balance(id: UUID): Long =
        jdbc.queryForObject("SELECT balance_minor FROM accounts WHERE id = ?", Long::class.java, id)!!
}
