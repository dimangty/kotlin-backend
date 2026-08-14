package ru.dbappweb

import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import ru.dbappweb.api.DesktopDemoApiClient
import ru.dbappweb.ui.DBAppWeb

/** Desktop-точка входа открывает общее Compose-приложение в самостоятельном JVM-окне. */
fun main() = application {
    // Начальный размер вмещает шесть карточек и двухпанельный экран примеров без тесной компоновки.
    val windowState = rememberWindowState(width = 1280.dp, height = 820.dp)

    Window(
        onCloseRequest = ::exitApplication,
        state = windowState,
        title = "DBAppWeb — лаборатория PostgreSQL",
    ) {
        // Один клиент живёт столько же, сколько композиция окна, и закрывается общим DisposableEffect.
        val apiClient = remember { DesktopDemoApiClient() }
        DBAppWeb(apiClient = apiClient)
    }
}
