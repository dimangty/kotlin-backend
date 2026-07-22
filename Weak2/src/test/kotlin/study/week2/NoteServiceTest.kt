package study.week2

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.UUID

class NoteServiceTest {
    private val service = NoteService(InMemoryNoteRepository())

    @Test
    fun `stale update is rejected`() {
        val created = service.create(CreateNoteRequest("title"))
        service.update(created.id, UpdateNoteRequest("new", version = created.version))
        assertThrows(StaleNote::class.java) {
            service.update(created.id, UpdateNoteRequest("stale", version = created.version))
        }
        assertEquals("new", service.get(created.id).title)
    }

    @Test
    fun `updating a missing note reports not found`() {
        assertThrows(NoteNotFound::class.java) {
            service.update(UUID.randomUUID(), UpdateNoteRequest("missing", version = 0))
        }
    }
}
