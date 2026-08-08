package study.db

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

// Точка входа сводной лаборатории по базам данных.
@SpringBootApplication
class Application

fun main(args: Array<String>) {
    runApplication<Application>(*args)
}
