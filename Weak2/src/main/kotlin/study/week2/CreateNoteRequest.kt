// Описывает входные данные для создания заметки.
// Компонент относится к учебному модулю недели 2 и раскрывает его основной пример.
package study.week2

import jakarta.validation.constraints.NotBlank
import org.springframework.web.bind.annotation.*

data class CreateNoteRequest(@field:NotBlank val title: String, val body: String = "")
