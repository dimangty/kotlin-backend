package study.week2

import org.springframework.web.bind.annotation.*
import java.util.UUID

interface NoteRepository {
    fun all(): List<Note>
    fun find(id: UUID): Note?
    fun save(note: Note, expectedVersion: Long? = null): Note
    fun delete(id: UUID): Boolean
}
