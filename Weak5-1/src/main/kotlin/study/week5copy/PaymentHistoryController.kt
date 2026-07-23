package study.week5copy

import jakarta.validation.Valid
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

@RestController
@RequestMapping("/api/payments")
class PaymentHistoryController(private val service: PaymentHistoryService) {
    @PostMapping("/generate")
    fun generate(@Valid @RequestBody request: GeneratePaymentsRequest) = mapOf("inserted" to service.generate(request))

    @GetMapping("/history")
    fun history(
        @RequestParam userId: Long,
        @RequestParam from: Instant,
        @RequestParam(defaultValue = "50") limit: Int,
    ) = service.history(userId, from, limit)

    @GetMapping("/pending")
    fun pending(
        @RequestParam before: Instant,
        @RequestParam(defaultValue = "50") limit: Int,
    ) = service.pendingBefore(before, limit)

    @GetMapping("/history/plan")
    fun historyPlan(@RequestParam userId: Long, @RequestParam from: Instant) = service.explainHistory(userId, from)

    @GetMapping("/indexes")
    fun indexes() = service.indexes()
}
