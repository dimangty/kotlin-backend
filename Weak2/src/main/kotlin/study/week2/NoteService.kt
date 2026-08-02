// Реализует сценарии создания, чтения и изменения заметок.
// Компонент относится к учебному модулю недели 2 и раскрывает его основной пример.
package study.week2

import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.*
import java.util.UUID

@Service
class NoteService(private val repository: NoteRepository) {
    // Возвращает все заметки в формате ответа API.
    fun all() = repository.all().map(::toResponse)
    // Возвращает заметку по идентификатору или сообщает об её отсутствии.
    fun get(id: UUID) = toResponse(repository.find(id) ?: throw NoteNotFound(id))
    // Создаёт новую заметку из входного запроса.
    fun create(request: CreateNoteRequest): NoteResponse =
        toResponse(repository.save(Note(UUID.randomUUID(), request.title.trim(), request.body, 0)))
    // Обновляет заметку с контролем её версии.
    fun update(id: UUID, request: UpdateNoteRequest): NoteResponse =
        toResponse(repository.save(Note(id, request.title.trim(), request.body, request.version), request.version))
    // Удаляет заметку или сообщает, что она не найдена.
    fun delete(id: UUID) { if (!repository.delete(id)) throw NoteNotFound(id) }
    // Преобразует доменную заметку в DTO ответа.
    private fun toResponse(note: Note) = NoteResponse(note.id, note.title, note.body, note.version)
}
