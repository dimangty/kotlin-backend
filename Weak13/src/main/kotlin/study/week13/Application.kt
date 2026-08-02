// Содержит точку запуска и основную конфигурацию приложения.
// Компонент относится к учебному модулю недели 13 и раскрывает его основной пример.
package study.week13

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class Application
// Запускает Spring Boot-приложение с проверкой готовности.
fun main(args: Array<String>) { runApplication<Application>(*args) }
