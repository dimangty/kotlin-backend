package ru.dbapp.model

/**
 * Параметры подключения вводятся на стартовом экране.
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

/**
 * Контракт общей части приложения с платформенной реализацией PostgreSQL.
 */
interface DemoRunner {
    /** Проверяет соединение и создаёт безопасную учебную схему, если её ещё нет. */
    suspend fun connect(settings: DatabaseSettings): ConnectionInfo

    /** Запускает выбранный сценарий и возвращает подробный пошаговый лог. */
    suspend fun runExample(exampleId: String, settings: DatabaseSettings): DemoReport
}
