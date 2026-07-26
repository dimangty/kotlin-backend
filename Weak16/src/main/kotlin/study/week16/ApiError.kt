// Задаёт единый формат ошибки, возвращаемой клиенту API.
// Компонент относится к учебному модулю недели 16 и раскрывает его основной пример.
package study.week16

import org.springframework.web.bind.annotation.*

data class ApiError(val code: String, val message: String)
