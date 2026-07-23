package study.week6copy

import jakarta.validation.constraints.Positive

data class DebitRequest(@field:Positive val amountMinor: Long)
