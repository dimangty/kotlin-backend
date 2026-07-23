package study.week16

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
class FintechController(private val service: FintechService) {
    @PostMapping("/accounts") @ResponseStatus(HttpStatus.CREATED) fun account(@RequestBody body: CreateAccount) = service.createAccount(body)
    @GetMapping("/accounts/{id}") fun account(@PathVariable id: UUID) = service.account(id)
    @PostMapping("/transfers") @ResponseStatus(HttpStatus.CREATED)
    fun transfer(@RequestHeader("Idempotency-Key") key: String, @RequestHeader("X-Actor") actor: String, @RequestBody body: CreateTransfer) = service.transfer(key, body, actor)
    @GetMapping("/transfers/{id}") fun transfer(@PathVariable id: UUID) = service.transfer(id)
    @GetMapping("/accounts/{id}/ledger")
    fun ledger(
        @PathVariable id: UUID,
        @RequestParam(required = false) cursor: Long?,
        @RequestParam(defaultValue = "50") limit: Int,
    ) = service.ledger(id, cursor, limit)
}
