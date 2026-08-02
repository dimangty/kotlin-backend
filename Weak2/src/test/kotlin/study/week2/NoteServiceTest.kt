// Проверяет бизнес-правила сервиса заметок изолированно.
// Тест относится к учебному модулю недели 2 и фиксирует ожидаемое поведение кода.
package study.week2

import org.junit.jupiter.api.TestInstance

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class NoteServiceTest {
    private val service = NoteService(InMemoryNoteRepository())

    @Test
    // Проверяет отклонение обновления с устаревшей версией заметки.
    fun `stale update is rejected`() {
        val created = service.create(CreateNoteRequest("title"))
        service.update(created.id, UpdateNoteRequest("new", version = created.version))
        assertThrows(StaleNote::class.java) {
            service.update(created.id, UpdateNoteRequest("stale", version = created.version))
        }
        assertEquals("new", service.get(created.id).title)
    }

    @Test
    // Проверяет ошибку при обновлении несуществующей заметки.
    fun `updating a missing note reports not found`() {
        assertThrows(NoteNotFound::class.java) {
            service.update(UUID.randomUUID(), UpdateNoteRequest("missing", version = 0))
        }
    }
}
