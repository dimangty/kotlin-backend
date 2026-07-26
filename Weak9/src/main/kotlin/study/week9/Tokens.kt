// Создаёт и проверяет учебные access- и refresh-токены.
// Компонент относится к учебному модулю недели 9 и раскрывает его основной пример.
package study.week9

import org.springframework.web.bind.annotation.*

data class Tokens(val accessToken: String, val refreshToken: String)
