package study.week5copy

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min

data class GeneratePaymentsRequest(
    @field:Min(1) @field:Max(100_000) val count: Int,
)
