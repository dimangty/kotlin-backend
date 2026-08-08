package ru.dbapp

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import ru.dbapp.data.PostgresDemoRunner
import ru.dbapp.model.DatabaseSettings
import ru.dbapp.ui.DBApp
import java.awt.Dimension

/**
 * Desktop-точка входа подставляет безопасные значения для стандартной установки PostgreSQL через brew.
 * Любое значение можно переопределить переменными окружения или полями стартового экрана.
 */
fun main() = application {
    val initialSettings = DatabaseSettings(
        url = System.getenv("DBAPP_DB_URL")
            ?: "jdbc:postgresql://localhost:5432/postgres?connectTimeout=5&ApplicationName=DBApp",
        user = System.getenv("DBAPP_DB_USER") ?: System.getProperty("user.name"),
        password = System.getenv("DBAPP_DB_PASSWORD") ?: "",
    )

    Window(
        onCloseRequest = ::exitApplication,
        title = "DBApp · PostgreSQL 18",
        state = WindowState(width = 1280.dp, height = 820.dp),
    ) {
        // Минимальный размер сохраняет читаемость двух панелей, но UI всё равно умеет складываться вертикально.
        window.minimumSize = Dimension(760, 640)
        DBApp(runner = rememberRunner(), initialSettings = initialSettings)
    }
}

/** Runner не хранит открытых соединений, поэтому один экземпляр безопасно обслуживает всё окно. */
@androidx.compose.runtime.Composable
private fun rememberRunner(): PostgresDemoRunner = androidx.compose.runtime.remember { PostgresDemoRunner() }
