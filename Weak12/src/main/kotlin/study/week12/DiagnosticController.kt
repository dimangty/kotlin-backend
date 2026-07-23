package study.week12

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
class DiagnosticController {
    @GetMapping("/work") fun work(@RequestParam(defaultValue = "10") millis: Long): Map<String, Long> {
        Thread.sleep(millis.coerceIn(0, 1000))
        return mapOf("durationRequestedMs" to millis)
    }
}
