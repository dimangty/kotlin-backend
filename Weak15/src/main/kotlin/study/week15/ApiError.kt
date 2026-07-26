// Задаёт единый формат ошибки, возвращаемой клиенту API.
// Компонент относится к учебному модулю недели 15 и раскрывает его основной пример.
package study.week15

import kotlinx.serialization.Serializable

@Serializable
data class ApiError(val code: String, val message: String)
