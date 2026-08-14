package ru.dbappweb

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import ru.dbappweb.api.BrowserDemoApiClient
import ru.dbappweb.ui.DBAppWeb

/** Браузерная точка входа создаёт canvas Compose внутри контейнера из index.html. */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    ComposeViewport(viewportContainerId = "ComposeTarget") {
        DBAppWeb(apiClient = BrowserDemoApiClient())
    }
}
