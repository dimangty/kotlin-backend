// Связывает HTTP API финансовых операций с прикладным сервисом.
// Компонент относится к учебному модулю недели 16 и раскрывает его основной пример.
package study.week16

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
class FintechController(private val service: FintechService) {
    // Создаёт новый счёт через HTTP API.
    @PostMapping("/accounts") @ResponseStatus(HttpStatus.CREATED) fun account(@RequestBody body: CreateAccount) = service.createAccount(body)
    // Возвращает счёт по идентификатору через HTTP API.
    @GetMapping("/accounts/{id}") fun account(@PathVariable id: UUID) = service.account(id)
    @PostMapping("/transfers") @ResponseStatus(HttpStatus.CREATED)
    // Создаёт идемпотентный перевод от имени указанного участника.
    fun transfer(@RequestHeader("Idempotency-Key") key: String, @RequestHeader("X-Actor") actor: String, @RequestBody body: CreateTransfer) = service.transfer(key, body, actor)
    // Возвращает перевод по идентификатору.
    @GetMapping("/transfers/{id}") fun transfer(@PathVariable id: UUID) = service.transfer(id)
    @GetMapping("/accounts/{id}/ledger")
    // Возвращает страницу проводок выбранного счёта.
    fun ledger(
        @PathVariable id: UUID,
        @RequestParam(required = false) cursor: Long?,
        @RequestParam(defaultValue = "50") limit: Int,
    ) = service.ledger(id, cursor, limit)
}
