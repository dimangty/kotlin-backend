package study.week14

import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

@Serializable data class Echo(val message: String)
class InvalidRequest(message: String) : RuntimeException(message)

fun Application.module() {
    install(ContentNegotiation) { json() }
    install(StatusPages) {
        exception<InvalidRequest> { call, cause -> call.respond(HttpStatusCode.BadRequest, mapOf("code" to "INVALID_REQUEST", "message" to cause.message)) }
    }
    install(Authentication) {
        bearer("auth") {
            // Учебная проверка заменяется JWT verifier; routing от этого не меняется.
            authenticate { credential -> credential.token.takeIf { it == "study-token" }?.let { UserIdPrincipal("student") } }
        }
    }
    routing {
        get("/health") { call.respond(mapOf("status" to "UP")) }
        post("/echo") { val body = call.receive<Echo>(); if (body.message.isBlank()) throw InvalidRequest("message is blank"); call.respond(body) }
        authenticate("auth") { get("/payments/{id}") { call.respond(mapOf("id" to call.parameters["id"], "status" to "PENDING")) } }
    }
}

fun main(args: Array<String>) { EngineMain.main(args) }

