// Описывает параметры массовой генерации тестовых событий.
// Компонент относится к учебному модулю недели 4 и раскрывает его основной пример.
package study.week4copy

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min

data class GenerateEventsRequest(
    // Ограничение не даёт случайно создать миллионы строк через один HTTP-запрос.
    @field:Min(1) @field:Max(100_000) val count: Int,
)
