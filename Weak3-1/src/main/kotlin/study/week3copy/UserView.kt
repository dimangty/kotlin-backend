// Задаёт безопасное внешнее представление пользователя.
// Компонент относится к учебному модулю недели 3 и раскрывает его основной пример.
package study.week3copy

import java.time.Instant
import java.util.UUID

data class UserView(val id: UUID, val email: String, val createdAt: Instant)
