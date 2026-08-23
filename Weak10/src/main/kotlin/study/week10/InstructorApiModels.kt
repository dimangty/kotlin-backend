package study.week10

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class InstructorRequest(
    @field:NotBlank(message = "must not be blank")
    @field:Size(max = 120, message = "must be at most 120 characters")
    val name: String,
)

data class InstructorResponse(
    val id: Long,
    val name: String,
)
