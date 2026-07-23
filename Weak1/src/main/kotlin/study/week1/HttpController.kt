package study.week1

import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.concurrent.TimeUnit

@RestController
class HttpController {
    @GetMapping("/health")
    fun health(): Map<String, String> = mapOf("status" to "UP")

    @GetMapping("/hello")
    fun hello(@RequestParam(defaultValue = "backend") name: String): ResponseEntity<Map<String, String>> =
        ResponseEntity.ok()
            // Cache-Control показывает, что семантика HTTP живет не только в JSON body.
            .cacheControl(CacheControl.maxAge(30, TimeUnit.SECONDS))
            .body(mapOf("message" to "Hello, $name!"))

    @PostMapping("/echo")
    fun echo(
        @RequestBody request: EchoRequest,
        @RequestHeader("X-Request-Id", required = false) requestId: String?,
    ): ResponseEntity<EchoResponse> =
        // POST здесь повторяем, но не идемпотентен по контракту: сервер не обещает один эффект.
        ResponseEntity.ok(EchoResponse(request.message, requestId))
}
