package study.week15

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.netty.EngineMain
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import java.util.UUID
import javax.sql.DataSource

@Serializable data class TransferRequest(val from: String, val to: String, val amountMinor: Long)
@Serializable data class TransferResult(val id: String, val status: String)

class TransferService(private val dataSource: DataSource) {
    fun transfer(request: TransferRequest): TransferResult = dataSource.connection.use { connection ->
        connection.autoCommit = false
        try {
            val ids = listOf(UUID.fromString(request.from), UUID.fromString(request.to)).sorted()
            connection.prepareStatement("SELECT id FROM accounts WHERE id IN (?,?) ORDER BY id FOR UPDATE").use { ps ->
                ps.setObject(1, ids[0]); ps.setObject(2, ids[1]); ps.executeQuery().use { rs -> var found = 0; while (rs.next()) found++; check(found == 2) }
            }
            val id = UUID.randomUUID()
            connection.prepareStatement("UPDATE accounts SET balance_minor=balance_minor + ? WHERE id=?").use { ps ->
                ps.setLong(1, -request.amountMinor); ps.setObject(2, UUID.fromString(request.from)); ps.executeUpdate()
                ps.setLong(1, request.amountMinor); ps.setObject(2, UUID.fromString(request.to)); ps.executeUpdate()
            }
            connection.commit()
            TransferResult(id.toString(), "COMPLETED")
        } catch (error: Exception) { connection.rollback(); throw error }
    }
}

fun Application.module() {
    install(ContentNegotiation) { json() }
    val config = HikariConfig().apply {
        jdbcUrl = System.getenv("DB_URL") ?: "jdbc:postgresql://localhost:5432/ktor"
        username = System.getenv("DB_USER") ?: "study"
        password = System.getenv("DB_PASSWORD") ?: "study"
        maximumPoolSize = 10
    }
    val dataSource = HikariDataSource(config)
    monitor.subscribe(ApplicationStopped) { dataSource.close() }
    val service = TransferService(dataSource)
    routing {
        get("/health") { call.respond(HttpStatusCode.OK, mapOf("status" to "UP")) }
        post("/transfers") { call.respond(HttpStatusCode.Created, service.transfer(call.receive())) }
    }
}

fun main(args: Array<String>) { EngineMain.main(args) }
