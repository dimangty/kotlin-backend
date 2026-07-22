package study.week15

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.netty.EngineMain
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.statuspages.exception
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.flywaydb.core.Flyway
import java.sql.Connection
import java.util.UUID
import javax.sql.DataSource

@Serializable
data class TransferRequest(val from: String, val to: String, val amountMinor: Long)

@Serializable
data class TransferResult(val id: String, val status: String)

@Serializable
data class ApiError(val code: String, val message: String)

private data class StoredTransfer(
    val result: TransferResult,
    val fromId: UUID,
    val toId: UUID,
    val amountMinor: Long,
)

class TransferService(private val dataSource: DataSource) {
    fun transfer(key: String, request: TransferRequest): TransferResult {
        require(key.isNotBlank() && key.length <= 128) { "invalid Idempotency-Key" }
        require(request.amountMinor > 0) { "amountMinor must be positive" }
        val fromId = UUID.fromString(request.from)
        val toId = UUID.fromString(request.to)
        require(fromId != toId) { "accounts must differ" }

        return inTransaction { connection ->
            findExisting(connection, key, fromId, toId, request.amountMinor)?.let { return@inTransaction it }

            // ORDER BY задаёт одинаковый порядок фактического захвата row locks для A->B и B->A.
            val balances = mutableMapOf<UUID, Long>()
            connection.prepareStatement(
                "SELECT id,balance_minor FROM accounts WHERE id IN (?,?) ORDER BY id FOR UPDATE",
            ).use { statement ->
                statement.setObject(1, fromId)
                statement.setObject(2, toId)
                statement.executeQuery().use { rows ->
                    while (rows.next()) {
                        balances[rows.getObject("id", UUID::class.java)] = rows.getLong("balance_minor")
                    }
                }
            }
            check(balances.size == 2) { "account not found" }

            // Конкурирующий запрос мог завершиться, пока этот запрос ждал account locks.
            findExisting(connection, key, fromId, toId, request.amountMinor)?.let { return@inTransaction it }
            check(balances.getValue(fromId) >= request.amountMinor) { "insufficient funds" }

            val transferId = UUID.randomUUID()
            val inserted = connection.prepareStatement(
                """
                INSERT INTO transfers(id,idempotency_key,from_account_id,to_account_id,amount_minor)
                VALUES (?,?,?,?,?)
                ON CONFLICT (idempotency_key) DO NOTHING
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, transferId)
                statement.setString(2, key)
                statement.setObject(3, fromId)
                statement.setObject(4, toId)
                statement.setLong(5, request.amountMinor)
                statement.executeUpdate()
            }
            if (inserted == 0) {
                return@inTransaction findExisting(connection, key, fromId, toId, request.amountMinor)
                    ?: error("idempotency conflict without a stored transfer")
            }

            connection.prepareStatement("UPDATE accounts SET balance_minor=balance_minor + ? WHERE id=?").use { statement ->
                statement.setLong(1, -request.amountMinor)
                statement.setObject(2, fromId)
                check(statement.executeUpdate() == 1)
                statement.setLong(1, request.amountMinor)
                statement.setObject(2, toId)
                check(statement.executeUpdate() == 1)
            }
            connection.prepareStatement(
                "INSERT INTO ledger_entries(transfer_id,account_id,amount_minor) VALUES (?,?,?),(?,?,?)",
            ).use { statement ->
                statement.setObject(1, transferId)
                statement.setObject(2, fromId)
                statement.setLong(3, -request.amountMinor)
                statement.setObject(4, transferId)
                statement.setObject(5, toId)
                statement.setLong(6, request.amountMinor)
                check(statement.executeUpdate() == 2)
            }
            TransferResult(transferId.toString(), "COMPLETED")
        }
    }

    private fun findExisting(
        connection: Connection,
        key: String,
        fromId: UUID,
        toId: UUID,
        amountMinor: Long,
    ): TransferResult? = connection.prepareStatement(
        "SELECT id,status,from_account_id,to_account_id,amount_minor FROM transfers WHERE idempotency_key=?",
    ).use { statement ->
        statement.setString(1, key)
        statement.executeQuery().use { rows ->
            if (!rows.next()) return@use null
            val stored = StoredTransfer(
                TransferResult(rows.getObject("id", UUID::class.java).toString(), rows.getString("status")),
                rows.getObject("from_account_id", UUID::class.java),
                rows.getObject("to_account_id", UUID::class.java),
                rows.getLong("amount_minor"),
            )
            require(stored.fromId == fromId && stored.toId == toId && stored.amountMinor == amountMinor) {
                "idempotency key was already used for another request"
            }
            stored.result
        }
    }

    private fun <T> inTransaction(block: (Connection) -> T): T = dataSource.connection.use { connection ->
        connection.autoCommit = false
        try {
            val result = block(connection)
            connection.commit()
            result
        } catch (error: Exception) {
            connection.rollback()
            throw error
        }
    }
}

fun Application.module() {
    val dataSource = dataSourceFromEnvironment()
    Flyway.configure().dataSource(dataSource).load().migrate()
    monitor.subscribe(ApplicationStopped) { dataSource.close() }
    configureHttp(TransferService(dataSource))
}

fun Application.configureHttp(service: TransferService) {
    install(ContentNegotiation) { json() }
    install(StatusPages) {
        exception<IllegalArgumentException> { call, error ->
            call.respond(HttpStatusCode.BadRequest, ApiError("INVALID_REQUEST", error.message ?: "invalid request"))
        }
        exception<IllegalStateException> { call, error ->
            call.respond(HttpStatusCode.Conflict, ApiError("TRANSFER_REJECTED", error.message ?: "transfer rejected"))
        }
    }
    routing {
        get("/health") { call.respond(HttpStatusCode.OK, mapOf("status" to "UP")) }
        post("/transfers") {
            val key = call.request.header("Idempotency-Key")
                ?: throw IllegalArgumentException("Idempotency-Key is required")
            val request = call.receive<TransferRequest>()
            // JDBC блокирует thread, поэтому он вынесен с Netty event loop.
            val result = withContext(Dispatchers.IO) { service.transfer(key, request) }
            call.respond(HttpStatusCode.Created, result)
        }
    }
}

private fun dataSourceFromEnvironment(): HikariDataSource = HikariDataSource(
    HikariConfig().apply {
        jdbcUrl = System.getenv("DB_URL") ?: "jdbc:postgresql://localhost:5432/ktor"
        username = System.getenv("DB_USER") ?: "study"
        password = System.getenv("DB_PASSWORD") ?: "study"
        maximumPoolSize = 10
        transactionIsolation = "TRANSACTION_READ_COMMITTED"
    },
)

fun main(args: Array<String>) {
    EngineMain.main(args)
}
