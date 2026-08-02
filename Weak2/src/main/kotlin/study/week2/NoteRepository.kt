// Объявляет контракт хранилища заметок.
// Компонент относится к учебному модулю недели 2 и раскрывает его основной пример.
package study.week2

import org.springframework.web.bind.annotation.*
import java.util.UUID

interface NoteRepository {
    // Возвращает все сохранённые заметки.
    fun all(): List<Note>
    // Ищет заметку по её идентификатору.
    fun find(id: UUID): Note?
    // Сохраняет заметку при совпадении ожидаемой версии.
    fun save(note: Note, expectedVersion: Long? = null): Note
    // Удаляет заметку по идентификатору.
    fun delete(id: UUID): Boolean
}
