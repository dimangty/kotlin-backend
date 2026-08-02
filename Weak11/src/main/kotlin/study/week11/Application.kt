// Содержит точку запуска и основную конфигурацию приложения.
// Компонент относится к учебному модулю недели 11 и раскрывает его основной пример.
package study.week11

import kotlinx.coroutines.runBlocking

// Запускает демонстрационный сценарий обработки платежа.
fun main() = runBlocking { println(PaymentCoordinator(MemoryRepository(), DemoGateway()).pay("demo", 100)) }
