package study.week2

import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
import java.util.UUID


@RestController
@RequestMapping("/notes")
class NoteController(private val service: NoteService) {
    @GetMapping fun all() = service.all()
    @GetMapping("/{id}") fun get(@PathVariable id: UUID) = service.get(id)
    @PostMapping @ResponseStatus(HttpStatus.CREATED) fun create(@Valid @RequestBody body: CreateNoteRequest) = service.create(body)
    @PutMapping("/{id}") fun update(@PathVariable id: UUID, @Valid @RequestBody body: UpdateNoteRequest) = service.update(id, body)
    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) fun delete(@PathVariable id: UUID) = service.delete(id)
}
