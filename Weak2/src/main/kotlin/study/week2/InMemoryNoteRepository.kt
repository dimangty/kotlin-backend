// Хранит заметки в памяти и атомарно контролирует их версии.
// Компонент относится к учебному модулю недели 2 и раскрывает его основной пример.
package study.week2

import org.springframework.stereotype.Repository
import org.springframework.web.bind.annotation.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Repository
class InMemoryNoteRepository : NoteRepository {
    private val notes = ConcurrentHashMap<UUID, Note>()
    // Возвращает все заметки, упорядоченные по идентификатору.
    override fun all(): List<Note> = notes.values.sortedBy { it.id }
    // Ищет заметку по идентификатору.
    override fun find(id: UUID): Note? = notes[id]

    // Сохраняет заметку с проверкой ожидаемой версии.
    override fun save(note: Note, expectedVersion: Long?): Note {
        // compute атомарен для одного ключа: проверка version и запись не разделяются гонкой.
        return notes.compute(note.id) { _, current ->
            if (expectedVersion != null) {
                if (current == null) throw NoteNotFound(note.id)
                if (current.version != expectedVersion) throw StaleNote()
            }
            note.copy(version = (current?.version ?: -1) + 1)
        }!!
    }

    // Удаляет заметку и сообщает, существовала ли она.
    override fun delete(id: UUID): Boolean = notes.remove(id) != null
}
