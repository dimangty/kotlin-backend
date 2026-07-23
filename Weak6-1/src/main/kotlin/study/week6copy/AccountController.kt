package study.week6copy

import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/accounts")
class AccountController(
    private val accounts: AccountService,
    private val serializableDebits: SerializableDebitService,
) {
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@Valid @RequestBody request: CreateAccountRequest) = accounts.create(request)

    @GetMapping("/{id}")
    fun balance(@PathVariable id: UUID) = accounts.balance(id)

    @PostMapping("/{id}/debits/atomic")
    fun atomicDebit(@PathVariable id: UUID, @Valid @RequestBody request: DebitRequest) =
        accounts.atomicDebit(id, request.amountMinor)

    @PostMapping("/{id}/debits/locked")
    fun lockedDebit(@PathVariable id: UUID, @Valid @RequestBody request: DebitRequest) =
        accounts.lockedDebit(id, request.amountMinor)

    @PostMapping("/{id}/debits/serializable")
    fun serializableDebit(@PathVariable id: UUID, @Valid @RequestBody request: DebitRequest) =
        serializableDebits.debit(id, request.amountMinor)
}
