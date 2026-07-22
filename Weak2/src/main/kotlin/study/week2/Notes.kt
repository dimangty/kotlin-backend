package study.week2

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.PositiveOrZero
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class Note(val id: UUID, val title: String, val body: String, val version: Long)
data class CreateNoteRequest(@field:NotBlank val title: String, val body: String = "")
data class UpdateNoteRequest(
    @field:NotBlank val title: String,
    val body: String = "",
    @field:PositiveOrZero val version: Long,
)
data class NoteResponse(val id: UUID, val title: String, val body: String, val version: Long)

class NoteNotFound(id: UUID) : RuntimeException("Note $id not found")
class StaleNote : RuntimeException("Note was changed by another request")

interface NoteRepository {
    fun all(): List<Note>
    fun find(id: UUID): Note?
    fun save(note: Note, expectedVersion: Long? = null): Note
    fun delete(id: UUID): Boolean
}

@Repository
class InMemoryNoteRepository : NoteRepository {
    private val notes = ConcurrentHashMap<UUID, Note>()
    override fun all(): List<Note> = notes.values.sortedBy { it.id }
    override fun find(id: UUID): Note? = notes[id]

    override fun save(note: Note, expectedVersion: Long?): Note {
        // compute атомарен для одного ключа: проверка version и запись не разделяются гонкой.
        return notes.compute(note.id) { _, current ->
            if (expectedVersion != null && current?.version != expectedVersion) throw StaleNote()
            note.copy(version = (current?.version ?: -1) + 1)
        }!!
    }

    override fun delete(id: UUID): Boolean = notes.remove(id) != null
}

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

@RestController
@RequestMapping("/notes")
class NoteController(private val service: NoteService) {
    @GetMapping fun all() = service.all()
    @GetMapping("/{id}") fun get(@PathVariable id: UUID) = service.get(id)
    @PostMapping @ResponseStatus(HttpStatus.CREATED) fun create(@Valid @RequestBody body: CreateNoteRequest) = service.create(body)
    @PutMapping("/{id}") fun update(@PathVariable id: UUID, @Valid @RequestBody body: UpdateNoteRequest) = service.update(id, body)
    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) fun delete(@PathVariable id: UUID) = service.delete(id)
}

