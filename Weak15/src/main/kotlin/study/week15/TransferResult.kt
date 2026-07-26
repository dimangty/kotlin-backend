// Представляет результат выполнения идемпотентного перевода.
// Компонент относится к учебному модулю недели 15 и раскрывает его основной пример.
package study.week15

import kotlinx.serialization.Serializable

@Serializable
data class TransferResult(val id: String, val status: String)
