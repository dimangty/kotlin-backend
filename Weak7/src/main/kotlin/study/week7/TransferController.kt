// Обрабатывает HTTP-запросы на денежные переводы.
// Компонент относится к учебному модулю недели 7 и раскрывает его основной пример.
package study.week7

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*

@RestController
class TransferController(private val service: TransferService) {
    @PostMapping("/transfers")
    @ResponseStatus(HttpStatus.CREATED)
    // Принимает HTTP-запрос на идемпотентный перевод средств.
    fun transfer(@RequestHeader("Idempotency-Key") key: String, @RequestBody request: TransferRequest) = service.transfer(key, request)
}
