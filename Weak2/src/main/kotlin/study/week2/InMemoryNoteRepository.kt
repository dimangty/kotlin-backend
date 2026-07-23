package study.week2

import org.springframework.stereotype.Repository
import org.springframework.web.bind.annotation.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Repository
class InMemoryNoteRepository : NoteRepository {
    private val notes = ConcurrentHashMap<UUID, Note>()
    override fun all(): List<Note> = notes.values.sortedBy { it.id }
    override fun find(id: UUID): Note? = notes[id]

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

    override fun delete(id: UUID): Boolean = notes.remove(id) != null
}
