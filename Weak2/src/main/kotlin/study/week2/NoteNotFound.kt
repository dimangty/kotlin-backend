// Сигнализирует об отсутствии запрошенной заметки.
// Компонент относится к учебному модулю недели 2 и раскрывает его основной пример.
package study.week2

import org.springframework.web.bind.annotation.*
import java.util.UUID

class NoteNotFound(id: UUID) : RuntimeException("Note $id not found")
