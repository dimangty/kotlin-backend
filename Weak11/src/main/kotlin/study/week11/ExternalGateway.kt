// Объявляет контракт взаимодействия с внешней платёжной системой.
// Компонент относится к учебному модулю недели 11 и раскрывает его основной пример.
package study.week11


interface ExternalGateway { suspend fun charge(key: String, amountMinor: Long): String }
