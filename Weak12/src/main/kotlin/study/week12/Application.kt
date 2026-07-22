package study.week12

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

@SpringBootApplication
class Application
fun main(args: Array<String>) { runApplication<Application>(*args) }

@Component
class RequestContextFilter(private val registry: MeterRegistry) : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val requestId = request.getHeader("X-Request-Id") ?: UUID.randomUUID().toString()
        val sample = Timer.start(registry)
        MDC.put("requestId", requestId)
        response.setHeader("X-Request-Id", requestId)
        try { chain.doFilter(request, response) }
        finally {
            // Не используем URL как tag: UUID в path создали бы metric-cardinality explosion.
            sample.stop(registry.timer("study.http.requests", "method", request.method, "status", response.status.toString()))
            MDC.remove("requestId")
        }
    }
}

@RestController
class DiagnosticController {
    @GetMapping("/work") fun work(@RequestParam(defaultValue = "10") millis: Long): Map<String, Long> {
        Thread.sleep(millis.coerceIn(0, 1000))
        return mapOf("durationRequestedMs" to millis)
    }
}

