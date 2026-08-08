package study.db

import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.time.Instant
import java.util.UUID

@RestController
@RequestMapping("/api/db")
class DatabaseController(
    private val accounts: AccountService,
    private val isolation: IsolationService,
    private val locks: LockService,
    private val indexes: IndexService,
) {
    @PostMapping("/accounts")
    @ResponseStatus(HttpStatus.CREATED)
    fun createAccount(@Valid @RequestBody request: CreateAccountRequest) = accounts.create(request)

    @GetMapping("/accounts/{id}")
    fun account(@PathVariable id: UUID) = accounts.get(id)

    @PostMapping("/accounts/{id}/atomic-debit")
    fun atomicDebit(@PathVariable id: UUID, @RequestParam amountMinor: Long) =
        accounts.atomicDebit(id, amountMinor)

    @PostMapping("/acid/transfers")
    fun acidTransfer(@Valid @RequestBody request: TransferRequest) = accounts.transfer(request)

    @GetMapping("/isolation/{id}")
    fun observeIsolation(
        @PathVariable id: UUID,
        @RequestParam(defaultValue = "READ_COMMITTED") level: DemoIsolation,
        @RequestParam(defaultValue = "5000") pauseMillis: Long,
    ) = isolation.observe(id, level, pauseMillis)

    @PostMapping("/anomalies/lost-update")
    fun lostUpdate(@Valid @RequestBody request: BalanceChangeRequest) =
        isolation.unsafeReadModifyWrite(request)

    @PostMapping("/isolation/serializable-change")
    fun serializableChange(@Valid @RequestBody request: BalanceChangeRequest) =
        isolation.serializableChange(request)

    @PostMapping("/locks/hold")
    fun holdLock(@Valid @RequestBody request: HoldLockRequest) = locks.hold(request)

    @PostMapping("/locks/transfers")
    fun lockedTransfer(@Valid @RequestBody request: TransferRequest) = locks.lockedTransfer(request)

    @PostMapping("/jobs/claim")
    fun claimJobs(@RequestParam(defaultValue = "10") limit: Int) = locks.claimJobs(limit)

    @PostMapping("/indexes/payments/generate")
    fun generatePayments(@Valid @RequestBody request: GeneratePaymentsRequest) =
        mapOf("inserted" to indexes.generate(request))

    @GetMapping("/indexes/payments/history")
    fun history(
        @RequestParam userId: Long,
        @RequestParam from: Instant,
        @RequestParam(defaultValue = "50") limit: Int,
    ) = indexes.history(userId, from, limit)

    @GetMapping("/indexes/payments/explain")
    fun explain(@RequestParam userId: Long, @RequestParam from: Instant) =
        indexes.explainHistory(userId, from)

    @GetMapping("/indexes")
    fun indexList() = indexes.indexes()
}

@RestControllerAdvice
class DatabaseErrorHandler {
    // Для лаборатории возвращаем понятную ошибку, а не HTML-страницу сервера.
    @ExceptionHandler(IllegalArgumentException::class, IllegalStateException::class, NoSuchElementException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun badRequest(error: RuntimeException) = ApiError("DATABASE_LAB_ERROR", error.message ?: "Ошибка")
}
