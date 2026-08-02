// Содержит точку запуска и основную конфигурацию приложения.
// Компонент относится к учебному модулю недели 16 и раскрывает его основной пример.
package study.week16

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class Application
// Запускает итоговое Spring Boot-приложение финансового сервиса.
fun main(args: Array<String>) { runApplication<Application>(*args) }
