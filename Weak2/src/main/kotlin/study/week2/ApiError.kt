// Задаёт единый формат ошибки, возвращаемой клиенту API.
// Компонент относится к учебному модулю недели 2 и раскрывает его основной пример.
package study.week2


data class ApiError(val code: String, val message: String, val details: Map<String, String>, val requestId: String)
