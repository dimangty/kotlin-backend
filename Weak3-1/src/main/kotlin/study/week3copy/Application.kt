// Содержит точку запуска и основную конфигурацию приложения.
// Компонент относится к учебному модулю недели 3 и раскрывает его основной пример.
package study.week3copy

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

// @SpringBootApplication включает auto-configuration, component scan и конфигурацию Spring.
// Точка входа намеренно маленькая: учебная логика находится в отдельных слоях ниже.
@SpringBootApplication
class Application

// Запускает Spring Boot-приложение учебного финансового сервиса.
fun main(args: Array<String>) {
    runApplication<Application>(*args)
}
