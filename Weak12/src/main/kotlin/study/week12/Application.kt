// Содержит точку запуска и основную конфигурацию приложения.
// Компонент относится к учебному модулю недели 12 и раскрывает его основной пример.
package study.week12

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class Application

// Запускает Spring Boot-приложение с корреляцией запросов.
fun main(args: Array<String>) { runApplication<Application>(*args) }
