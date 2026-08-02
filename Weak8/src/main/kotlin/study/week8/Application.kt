// Содержит точку запуска и основную конфигурацию приложения.
// Компонент относится к учебному модулю недели 8 и раскрывает его основной пример.
package study.week8

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class Application
// Запускает Spring Boot-приложение обработки платежей.
fun main(args: Array<String>) { runApplication<Application>(*args) }
