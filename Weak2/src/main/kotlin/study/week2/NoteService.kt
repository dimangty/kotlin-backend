package study.week2

import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.*
import java.util.UUID

@Service
class NoteService(private val repository: NoteRepository) {
    fun all() = repository.all().map(::toResponse)
    fun get(id: UUID) = toResponse(repository.find(id) ?: throw NoteNotFound(id))
    fun create(request: CreateNoteRequest): NoteResponse =
        toResponse(repository.save(Note(UUID.randomUUID(), request.title.trim(), request.body, 0)))
    fun update(id: UUID, request: UpdateNoteRequest): NoteResponse =
        toResponse(repository.save(Note(id, request.title.trim(), request.body, request.version), request.version))
    fun delete(id: UUID) { if (!repository.delete(id)) throw NoteNotFound(id) }
    private fun toResponse(note: Note) = NoteResponse(note.id, note.title, note.body, note.version)
}
