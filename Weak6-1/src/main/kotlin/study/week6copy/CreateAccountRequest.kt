package study.week6copy

import jakarta.validation.constraints.PositiveOrZero

data class CreateAccountRequest(@field:PositiveOrZero val initialBalanceMinor: Long)
