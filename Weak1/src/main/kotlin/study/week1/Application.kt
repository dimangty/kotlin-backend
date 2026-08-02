// Содержит точку запуска и основную конфигурацию приложения.
// Компонент относится к учебному модулю недели 1 и раскрывает его основной пример.
package study.week1

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class Application

// Запускает Spring Boot-приложение с переданными аргументами командной строки.
fun main(args: Array<String>) {
    runApplication<Application>(*args)
}
