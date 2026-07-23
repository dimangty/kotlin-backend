package study.week3copy

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
@RequestMapping("/api")
class FintechController(private val service: FintechService) {
    @PostMapping("/users")
    @ResponseStatus(HttpStatus.CREATED)
    fun createUser(@Valid @RequestBody request: CreateUserRequest) = service.createUser(request)

    @PostMapping("/accounts")
    @ResponseStatus(HttpStatus.CREATED)
    fun openAccount(@Valid @RequestBody request: OpenAccountRequest) = service.openAccount(request)

    @PostMapping("/payments")
    @ResponseStatus(HttpStatus.CREATED)
    fun createPayment(@Valid @RequestBody request: CreatePaymentRequest) = service.createPayment(request)

    @GetMapping("/accounts/{id}/snapshot")
    fun accountSnapshot(@PathVariable id: UUID) = service.accountSnapshot(id)

    @GetMapping("/accounts/{id}/physical-tuple")
    fun physicalTuple(@PathVariable id: UUID) = service.physicalTuple(id)
}
