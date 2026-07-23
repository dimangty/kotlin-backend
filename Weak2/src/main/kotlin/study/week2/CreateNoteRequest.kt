package study.week2

import jakarta.validation.constraints.NotBlank
import org.springframework.web.bind.annotation.*

data class CreateNoteRequest(@field:NotBlank val title: String, val body: String = "")
