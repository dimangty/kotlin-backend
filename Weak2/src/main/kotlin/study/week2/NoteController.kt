// Обрабатывает CRUD-запросы к заметкам.
// Компонент относится к учебному модулю недели 2 и раскрывает его основной пример.
package study.week2

import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
import java.util.UUID


@RestController
@RequestMapping("/notes")
class NoteController(private val service: NoteService) {
    // Возвращает список всех заметок через HTTP API.
    @GetMapping fun all() = service.all()
    // Возвращает одну заметку по идентификатору.
    @GetMapping("/{id}") fun get(@PathVariable id: UUID) = service.get(id)
    // Создаёт заметку из проверенного тела запроса.
    @PostMapping @ResponseStatus(HttpStatus.CREATED) fun create(@Valid @RequestBody body: CreateNoteRequest) = service.create(body)
    // Обновляет существующую заметку по идентификатору.
    @PutMapping("/{id}") fun update(@PathVariable id: UUID, @Valid @RequestBody body: UpdateNoteRequest) = service.update(id, body)
    // Удаляет заметку по идентификатору.
    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) fun delete(@PathVariable id: UUID) = service.delete(id)
}
