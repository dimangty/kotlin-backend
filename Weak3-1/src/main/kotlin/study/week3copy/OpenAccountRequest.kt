package study.week3copy

import jakarta.validation.constraints.Pattern
import java.util.UUID

data class OpenAccountRequest(
    val ownerId: UUID,
    // Формат валюты защищается и здесь для быстрой ошибки API, и CHECK-constraint в PostgreSQL.
    @field:Pattern(regexp = "[A-Z]{3}") val currency: String,
)
