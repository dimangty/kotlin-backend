package study.week10

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size

data class CourseRequest(
    @field:NotBlank(message = "must not be blank")
    @field:Size(max = 200, message = "must be at most 200 characters")
    val name: String,

    @field:NotBlank(message = "must not be blank")
    @field:Size(max = 100, message = "must be at most 100 characters")
    val category: String,

    @field:Positive(message = "must be greater than 0")
    val instructorId: Long,
)

data class CourseResponse(
    val id: Long,
    val name: String,
    val category: String,
    val instructorId: Long,
    val instructorName: String,
)
