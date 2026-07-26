// Описывает данные и ожидаемую версию обновляемой заметки.
// Компонент относится к учебному модулю недели 2 и раскрывает его основной пример.
package study.week2

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.PositiveOrZero
import org.springframework.web.bind.annotation.*

data class UpdateNoteRequest(
    @field:NotBlank val title: String,
    val body: String = "",
    @field:PositiveOrZero val version: Long,
)
