// Описывает пользователя и данные, необходимые для проверки его пароля.
// Компонент относится к учебному модулю недели 9 и раскрывает его основной пример.
package study.week9

import org.springframework.web.bind.annotation.*
import java.util.UUID

data class User(val id: UUID, val email: String, val passwordHash: String)
