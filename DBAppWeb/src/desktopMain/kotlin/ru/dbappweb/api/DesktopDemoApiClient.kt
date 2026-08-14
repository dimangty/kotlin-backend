package ru.dbappweb.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import ru.dbappweb.model.DemoApiClient

/** JVM Desktop обращается к тому же loopback API, что и браузерный клиент. */
internal class DesktopDemoApiClient(
    baseUrl: String = desktopApiUrl(),
) : DemoApiClient by KtorDemoApiClient(
    baseUrl = baseUrl,
    client = HttpClient(CIO) {
        // HTTP-ошибки превращаются в исключения и отображаются внутри общего поля логов.
        expectSuccess = true
        install(ContentNegotiation) {
            json(
                Json {
                    // Новые необязательные поля ответа не ломают уже установленный desktop-клиент.
                    ignoreUnknownKeys = true
                },
            )
        }
    },
)

/**
 * Системное свойство удобно для запуска из Gradle, а переменная окружения — для готового приложения.
 * Значение по умолчанию совпадает с loopback-портом Docker Compose.
 */
private fun desktopApiUrl(): String =
    System.getProperty("dbappweb.api.url")
        ?: System.getenv("DBAPPWEB_API_URL")
        ?: "http://127.0.0.1:18082"
