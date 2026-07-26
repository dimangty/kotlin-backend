// Описывает запрос на открытие нового счёта.
// Компонент относится к учебному модулю недели 6 и раскрывает его основной пример.
package study.week6copy

import jakarta.validation.constraints.PositiveOrZero

data class CreateAccountRequest(@field:PositiveOrZero val initialBalanceMinor: Long)
