// Описывает параметры генерации платёжной истории.
// Компонент относится к учебному модулю недели 5 и раскрывает его основной пример.
package study.week5copy

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min

data class GeneratePaymentsRequest(
    @field:Min(1) @field:Max(100_000) val count: Int,
)
