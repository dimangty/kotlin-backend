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
import org.flywaydb.core.Flyway

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
