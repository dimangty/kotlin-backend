// Задаёт единый формат ошибки, возвращаемой клиенту API.
// Компонент относится к учебному модулю недели 6 и раскрывает его основной пример.
package study.week6copy


data class ApiError(val code: String, val message: String)
