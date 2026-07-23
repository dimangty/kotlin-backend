package study.week9

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.web.bind.annotation.*

data class Credentials(
    @field:Email @field:NotBlank val email: String,
    @field:Size(min = 12, max = 128) val password: String,
)
