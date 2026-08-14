package ru.dbappweb.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.browser.window
import kotlinx.serialization.json.Json
import ru.dbappweb.model.DemoApiClient

/** Браузерная реализация обращается только к локальному Spring Boot API на порту 18082. */
internal class BrowserDemoApiClient(
    baseUrl: String = "http://${window.location.hostname.ifBlank { "localhost" }}:18082",
) : DemoApiClient by KtorDemoApiClient(
    baseUrl = baseUrl,
    client = HttpClient(Js) {
        // Любой HTTP 4xx/5xx превращается в исключение и попадает в видимый лог Compose UI.
        expectSuccess = true
        install(ContentNegotiation) {
            json(
                Json {
                    // Дополнительные поля будущей версии API не должны ломать старый клиент.
                    ignoreUnknownKeys = true
                },
            )
        }
    },
)
