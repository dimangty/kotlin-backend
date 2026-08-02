// Содержит точку запуска и основную конфигурацию приложения.
// Компонент относится к учебному модулю недели 6 и раскрывает его основной пример.
package study.week6copy

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class Application

// Запускает Spring Boot-приложение конкурентных операций со счетами.
fun main(args: Array<String>) {
    runApplication<Application>(*args)
}
