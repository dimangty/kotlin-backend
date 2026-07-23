package study.week9

import jakarta.validation.constraints.PositiveOrZero
import org.springframework.web.bind.annotation.*

data class CreateAccount(@field:PositiveOrZero val balanceMinor: Long = 0)
