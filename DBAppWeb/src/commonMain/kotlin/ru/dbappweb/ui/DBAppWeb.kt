package ru.dbappweb.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch
import ru.dbappweb.model.ConnectionInfo
import ru.dbappweb.model.DemoApiClient
import ru.dbappweb.model.DemoExample
import ru.dbappweb.model.DemoTopic

/** Корневой composable хранит навигацию и координирует все обращения к Docker-бэкенду. */
@Composable
fun DBAppWeb(apiClient: DemoApiClient) {
    val scope = rememberCoroutineScope()
    var selectedTopic by remember { mutableStateOf<DemoTopic?>(null) }
    var connectionInfo by remember { mutableStateOf<ConnectionInfo?>(null) }
    var isBusy by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("Проверяем локальный Spring Boot API...") }
    var logText by remember { mutableStateOf("Выберите тему и учебный пример.") }

    /** Повторная проверка нужна после запуска или перезапуска docker compose. */
    fun refreshConnection() {
        if (isBusy) return
        scope.launch {
            isBusy = true
            statusText = "Проверяем REST API и PostgreSQL..."
            try {
                connectionInfo = apiClient.connectionInfo()
                statusText = "Бэкенд и учебная схема dbapp_lab готовы."
            } catch (error: Throwable) {
                connectionInfo = null
                statusText = "Нет соединения: ${error.message ?: error::class.simpleName}"
            } finally {
                isBusy = false
            }
        }
    }

    /** UI запускает только один изменяющий состояние стенда пример за раз. */
    fun runExample(example: DemoExample) {
        if (isBusy || connectionInfo == null) return
        scope.launch {
            isBusy = true
            logText = "[RUN] ${example.title}\nКлиент отправляет два параллельных POST-запроса..."
            try {
                val report = apiClient.runExample(example.id)
                logText = buildString {
                    appendLine(if (report.successful) "[OK] ${report.title}" else "[WARN] ${report.title}")
                    appendLine()
                    // Нормализация сохраняет одинаковый вид логов в браузерном и desktop Canvas UI.
                    append(report.lines.joinToString("\n").displaySafe())
                }
                statusText = if (report.successful) {
                    "Пример завершён; изменения ограничены схемой dbapp_lab."
                } else {
                    "Пример завершён с диагностической ошибкой; подробности находятся в логе."
                }
            } catch (error: Throwable) {
                logText = "[ERROR] ${example.title}\n\nОшибка REST API: ${error.message ?: error::class.simpleName}"
                statusText = "Не удалось выполнить пример. Проверьте docker compose."
            } finally {
                isBusy = false
            }
        }
    }

    // Первая проверка избавляет пользователя от отдельного шага после docker compose up.
    LaunchedEffect(Unit) {
        refreshConnection()
    }

    // Закрытие Ktor-клиента освобождает платформенные сетевые ресурсы.
    DisposableEffect(apiClient) {
        onDispose { apiClient.close() }
    }

    DBAppTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            val topic = selectedTopic
            if (topic == null) {
                HomeScreen(
                    connectionInfo = connectionInfo,
                    statusText = statusText,
                    isBusy = isBusy,
                    onRefresh = ::refreshConnection,
                    onTopicSelected = { selected ->
                        selectedTopic = selected
                        logText = "Раздел «${selected.title}». Выберите пример слева."
                    },
                )
            } else {
                TopicScreen(
                    topic = topic,
                    logText = logText,
                    statusText = statusText,
                    isBusy = isBusy,
                    canRun = connectionInfo != null,
                    onBack = { selectedTopic = null },
                    onClearLog = { logText = "Лог очищен. Выберите следующий пример." },
                    onExampleSelected = ::runExample,
                )
            }
        }
    }
}

/** Общий ASCII-формат гарантированно выглядит одинаково во встроенных шрифтах Web и Desktop. */
private fun String.displaySafe(): String =
    replace("✓", "[OK]")
        .replace("⚠", "[EXPECTED]")
        .replace("✗", "[ERROR]")
        .replace("→", "->")
