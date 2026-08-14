package ru.dbapp.model

/**
 * Параметры подключения поступают из типизированной Spring Boot-конфигурации.
 * Пароль никогда не добавляется в учебный лог.
 */
data class DatabaseSettings(
    val url: String,
    val user: String,
    val password: String,
)

/**
 * Результат успешной проверки соединения нужен UI для компактной карточки состояния.
 */
data class ConnectionInfo(
    val serverVersion: String,
    val database: String,
    val user: String,
)

/**
 * Отчёт одного примера уже подготовлен для показа в многострочном поле логов.
 */
data class DemoReport(
    val title: String,
    val lines: List<String>,
    val successful: Boolean,
)

/**
 * Ответ одного HTTP-участника содержит собственный журнал барьера.
 * Полный SQL-лог прикладывает только запрос, выбранный исполнителем общего JDBC-сценария.
 */
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

/**
 * Описание кнопки примера отделено от JDBC-кода, чтобы общий UI оставался multiplatform.
 */
data class DemoExample(
    val id: String,
    val title: String,
    val description: String,
)

/**
 * Модель тематического экрана: заголовок, учебная подсказка и набор примеров из PDF.
 */
data class DemoTopic(
    val id: String,
    val title: String,
    val subtitle: String,
    val examples: List<DemoExample>,
)
