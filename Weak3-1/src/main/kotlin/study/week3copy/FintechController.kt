// Связывает HTTP API финансовых операций с прикладным сервисом.
// Компонент относится к учебному модулю недели 3 и раскрывает его основной пример.
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
    // Обрабатывает HTTP-запрос на создание пользователя.
    fun createUser(@Valid @RequestBody request: CreateUserRequest) = service.createUser(request)

    @PostMapping("/accounts")
    @ResponseStatus(HttpStatus.CREATED)
    // Обрабатывает HTTP-запрос на открытие счёта.
    fun openAccount(@Valid @RequestBody request: OpenAccountRequest) = service.openAccount(request)

    @PostMapping("/payments")
    @ResponseStatus(HttpStatus.CREATED)
    // Обрабатывает HTTP-запрос на создание платежа.
    fun createPayment(@Valid @RequestBody request: CreatePaymentRequest) = service.createPayment(request)

    @GetMapping("/accounts/{id}/snapshot")
    // Возвращает снимок состояния счёта по идентификатору.
    fun accountSnapshot(@PathVariable id: UUID) = service.accountSnapshot(id)

    @GetMapping("/accounts/{id}/physical-tuple")
    // Возвращает диагностические данные физической строки счёта.
    fun physicalTuple(@PathVariable id: UUID) = service.physicalTuple(id)
}
