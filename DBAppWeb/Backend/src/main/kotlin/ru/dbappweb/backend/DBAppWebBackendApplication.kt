package ru.dbappweb.backend

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication
import ru.dbappweb.backend.config.DemoDatabaseProperties

/** Spring Boot-композиция сканирует REST, конфигурацию и JDBC-сервис лабораторий. */
@SpringBootApplication(scanBasePackages = ["ru.dbappweb", "ru.dbapp"])
@EnableConfigurationProperties(DemoDatabaseProperties::class)
class DBAppWebBackendApplication

/** Единственная JVM-точка входа используется и bootRun, и Docker image. */
fun main(args: Array<String>) {
    runApplication<DBAppWebBackendApplication>(*args)
}
