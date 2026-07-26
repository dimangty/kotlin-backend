// Описывает запрос на списание средств со счёта.
// Компонент относится к учебному модулю недели 6 и раскрывает его основной пример.
package study.week6copy

import jakarta.validation.constraints.Positive

data class DebitRequest(@field:Positive val amountMinor: Long)
