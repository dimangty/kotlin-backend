// Описывает входные данные денежного перевода.
// Компонент относится к учебному модулю недели 15 и раскрывает его основной пример.
package study.week15

import kotlinx.serialization.Serializable

@Serializable
data class TransferRequest(val from: String, val to: String, val amountMinor: Long)
