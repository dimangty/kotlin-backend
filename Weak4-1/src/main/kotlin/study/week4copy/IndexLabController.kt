package study.week4copy

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
@RequestMapping("/api/index-lab")
class IndexLabController(private val service: IndexLabService) {
    @PostMapping("/events/generate")
    @ResponseStatus(HttpStatus.CREATED)
    fun generate(@Valid @RequestBody request: GenerateEventsRequest) = mapOf("inserted" to service.generate(request))

    @GetMapping("/events/{publicId}")
    fun find(@PathVariable publicId: UUID) = service.findByPublicId(publicId)

    @GetMapping("/events/{publicId}/plan")
    fun plan(@PathVariable publicId: UUID) = service.explainUuidLookup(publicId)

    @GetMapping("/distribution")
    fun distribution() = service.statusDistribution()

    @GetMapping("/sizes")
    fun sizes() = service.sizes()
}
