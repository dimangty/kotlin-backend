package study.week7

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*

@RestController
class TransferController(private val service: TransferService) {
    @PostMapping("/transfers")
    @ResponseStatus(HttpStatus.CREATED)
    fun transfer(@RequestHeader("Idempotency-Key") key: String, @RequestBody request: TransferRequest) = service.transfer(key, request)
}
