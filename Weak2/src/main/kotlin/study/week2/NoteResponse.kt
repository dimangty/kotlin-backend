// Задаёт представление заметки, возвращаемое клиенту.
// Компонент относится к учебному модулю недели 2 и раскрывает его основной пример.
package study.week2

import org.springframework.web.bind.annotation.*
import java.util.UUID

data class NoteResponse(val id: UUID, val title: String, val body: String, val version: Long)
