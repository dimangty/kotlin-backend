package study.week12

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

@Component
class RequestCorrelationFilter(private val registry: MeterRegistry) : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val requestId = safeId(request.getHeader("X-Request-Id"))
        val operationId = safeId(request.getHeader("X-Operation-Id"))
        val sample = Timer.start(registry)
        MDC.put("requestId", requestId)
        MDC.put("operationId", operationId)
        response.setHeader("X-Request-Id", requestId)
        response.setHeader("X-Operation-Id", operationId)
        try { chain.doFilter(request, response) }
        finally {
            // Не используем URL как tag: UUID в path создали бы metric-cardinality explosion.
            sample.stop(registry.timer("study.http.requests", "method", request.method, "status", response.status.toString()))
            MDC.remove("requestId")
            MDC.remove("operationId")
        }
    }

    private fun safeId(candidate: String?): String =
        candidate?.takeIf { it.length <= 128 && it.matches(SAFE_ID) } ?: UUID.randomUUID().toString()

    private companion object {
        val SAFE_ID = Regex("[A-Za-z0-9._:-]+")
    }
}
