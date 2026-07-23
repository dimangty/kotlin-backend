package study.week2

import org.springframework.web.bind.annotation.*
import java.util.UUID

class NoteNotFound(id: UUID) : RuntimeException("Note $id not found")
