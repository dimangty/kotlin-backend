package ru.dbappweb.backend.config

import org.springframework.boot.context.properties.ConfigurationProperties

/** Типизированные настройки исключают размазывание имён переменных окружения по JDBC-коду. */
@ConfigurationProperties(prefix = "demo.database")
data class DemoDatabaseProperties(
    /** Локально значение указывает на Homebrew PostgreSQL, а Docker Compose подменяет hostname на postgres. */
    val url: String = "jdbc:postgresql://localhost:5432/postgres",
    /** Для Docker используется отдельная учебная роль; локально значение можно переопределить. */
    val user: String = System.getProperty("user.name"),
    /** Пустой пароль соответствует типичной локальной Homebrew-конфигурации peer/trust. */
    val password: String = "",
)
