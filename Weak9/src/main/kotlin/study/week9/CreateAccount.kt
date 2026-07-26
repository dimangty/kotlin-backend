// Описывает входные данные для создания новой учётной записи.
// Компонент относится к учебному модулю недели 9 и раскрывает его основной пример.
package study.week9

import jakarta.validation.constraints.PositiveOrZero
import org.springframework.web.bind.annotation.*

data class CreateAccount(@field:PositiveOrZero val balanceMinor: Long = 0)
