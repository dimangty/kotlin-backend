// Содержит точку запуска и основную конфигурацию приложения.
// Компонент относится к учебному модулю недели 4 и раскрывает его основной пример.
package study.week4copy

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class Application

fun main(args: Array<String>) {
    runApplication<Application>(*args)
}
