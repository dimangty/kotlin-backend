package ru.dbappweb.model

import kotlinx.serialization.Serializable

/** Краткая информация подтверждает, что клиент дошёл до бэкенда и PostgreSQL. */
@Serializable
data class ConnectionInfo(
    val serverVersion: String,
    val database: String,
    val user: String,
)

/** Отчёт одного REST-сценария уже подготовлен для многострочного поля логов. */
@Serializable
data class DemoReport(
    val title: String,
    val lines: List<String>,
    val successful: Boolean,
)

/** Один ответ относится к одному из параллельных HTTP-запросов общего учебного запуска. */
@Serializable
data class ParallelParticipantReport(
    val runId: String,
    val participant: Int,
    val participantCount: Int,
    val executorParticipant: Int,
    val title: String,
    val lines: List<String>,
    val successful: Boolean,
    val durationMs: Long,
)

/** Описание кнопки хранится отдельно от API, чтобы стартовый UI открывался даже при недоступном Docker. */
data class DemoExample(
    val id: String,
    val title: String,
    val description: String,
)

/** Тематический экран объединяет заголовок, учебную подсказку и примеры из PDF. */
data class DemoTopic(
    val id: String,
    val title: String,
    val subtitle: String,
    val examples: List<DemoExample>,
)

/** Контракт скрывает платформенный HTTP-движок от общего Compose UI. */
interface DemoApiClient {
    /** Проверяет одновременно доступность REST API и его подключения к PostgreSQL. */
    suspend fun connectionInfo(): ConnectionInfo

    /** Параллельно отправляет участников запуска и объединяет возвращённые бэкендом логи. */
    suspend fun runExample(exampleId: String): DemoReport

    /** Освобождает ресурсы HTTP-клиента при закрытии клиентской композиции. */
    fun close()
}
