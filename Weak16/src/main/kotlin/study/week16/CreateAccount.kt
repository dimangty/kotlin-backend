// Описывает входные данные для создания новой учётной записи.
// Компонент относится к учебному модулю недели 16 и раскрывает его основной пример.
package study.week16

import org.springframework.web.bind.annotation.*
import java.util.UUID

data class CreateAccount(val ownerId: UUID, val currency: String, val initialBalanceMinor: Long = 0)
