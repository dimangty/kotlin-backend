// Задаёт внешнее представление счёта для ответа API.
// Компонент относится к учебному модулю недели 16 и раскрывает его основной пример.
package study.week16

import org.springframework.web.bind.annotation.*
import java.util.UUID

data class AccountView(val id: UUID, val ownerId: UUID, val currency: String, val balanceMinor: Long)
