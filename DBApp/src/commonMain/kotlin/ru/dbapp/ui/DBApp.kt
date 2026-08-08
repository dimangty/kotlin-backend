package ru.dbapp.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch
import ru.dbapp.model.ConnectionInfo
import ru.dbapp.model.DatabaseSettings
import ru.dbapp.model.DemoExample
import ru.dbapp.model.DemoRunner
import ru.dbapp.model.DemoTopic

/**
 * Корневой composable хранит только состояние интерфейса.
 * Все блокирующие JDBC-операции выполняет переданный платформенный [DemoRunner].
 */
@Composable
fun DBApp(
    runner: DemoRunner,
    initialSettings: DatabaseSettings,
) {
    val scope = rememberCoroutineScope()

    // Настройки редактируются локально и применяются при подключении или запуске примера.
    var url by remember { mutableStateOf(initialSettings.url) }
    var user by remember { mutableStateOf(initialSettings.user) }
    var password by remember { mutableStateOf(initialSettings.password) }

    // null означает стартовый экран; объект темы означает открытый учебный раздел.
    var selectedTopic by remember { mutableStateOf<DemoTopic?>(null) }
    var connectionInfo by remember { mutableStateOf<ConnectionInfo?>(null) }
    var statusText by remember { mutableStateOf("Проверяем локальный PostgreSQL…") }
    var isBusy by remember { mutableStateOf(false) }
    var logText by remember {
        mutableStateOf("Выберите пример. Здесь появятся SQL-команды, результаты и объяснение механизма PostgreSQL.")
    }

    /** Возвращает снимок полей, чтобы один запуск не смешал старые и новые параметры. */
    fun currentSettings(): DatabaseSettings = DatabaseSettings(
        url = url.trim(),
        user = user.trim(),
        password = password,
    )

    /** Подключение создаёт только отдельную схему dbapp_lab и сообщает версию сервера. */
    fun connect() {
        if (isBusy) return
        scope.launch {
            isBusy = true
            statusText = "Подключение…"
            try {
                connectionInfo = runner.connect(currentSettings())
                statusText = "Подключено. Учебная схема dbapp_lab готова."
            } catch (error: Throwable) {
                connectionInfo = null
                statusText = "Нет соединения: ${error.message ?: error::class.simpleName}"
            } finally {
                isBusy = false
            }
        }
    }

    /** Один пример выполняется за раз, чтобы лабораторные транзакции не влияли друг на друга. */
    fun runExample(example: DemoExample) {
        if (isBusy) return
        scope.launch {
            isBusy = true
            logText = "▶ ${example.title}\nПодготовка учебного состояния…"
            try {
                val report = runner.runExample(example.id, currentSettings())
                logText = buildString {
                    appendLine(if (report.successful) "✓ ${report.title}" else "⚠ ${report.title}")
                    appendLine()
                    append(report.lines.joinToString("\n"))
                }
                statusText = if (report.successful) {
                    "Пример завершён. Изменения ограничены схемой dbapp_lab."
                } else {
                    "Пример завершён с диагностической ошибкой; подробности находятся в логе."
                }
            } catch (error: Throwable) {
                logText = "✗ ${example.title}\n\nНепредвиденная ошибка: ${error.message ?: error::class.simpleName}"
                statusText = "Не удалось выполнить пример."
            } finally {
                isBusy = false
            }
        }
    }

    // Первая проверка избавляет пользователя от обязательного ручного шага при стандартной brew-конфигурации.
    LaunchedEffect(Unit) {
        connect()
    }

    DBAppTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            if (selectedTopic == null) {
                HomeScreen(
                    url = url,
                    user = user,
                    password = password,
                    onUrlChange = { url = it },
                    onUserChange = { user = it },
                    onPasswordChange = { password = it },
                    connectionInfo = connectionInfo,
                    statusText = statusText,
                    isBusy = isBusy,
                    onConnect = ::connect,
                    onTopicSelected = { topic ->
                        selectedTopic = topic
                        logText = "Раздел «${topic.title}». Выберите пример слева."
                    },
                )
            } else {
                TopicScreen(
                    topic = selectedTopic!!,
                    logText = logText,
                    statusText = statusText,
                    isBusy = isBusy,
                    onBack = { selectedTopic = null },
                    onClearLog = { logText = "Лог очищен. Выберите следующий пример." },
                    onExampleSelected = ::runExample,
                )
            }
        }
    }
}
