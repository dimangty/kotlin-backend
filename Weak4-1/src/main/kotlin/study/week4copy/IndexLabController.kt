// Открывает HTTP API для экспериментов с индексами PostgreSQL.
// Компонент относится к учебному модулю недели 4 и раскрывает его основной пример.
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
    // Запускает генерацию событий через HTTP API.
    fun generate(@Valid @RequestBody request: GenerateEventsRequest) = mapOf("inserted" to service.generate(request))

    @GetMapping("/events/{publicId}")
    // Возвращает событие по публичному идентификатору.
    fun find(@PathVariable publicId: UUID) = service.findByPublicId(publicId)

    @GetMapping("/events/{publicId}/plan")
    // Возвращает план запроса поиска по UUID.
    fun plan(@PathVariable publicId: UUID) = service.explainUuidLookup(publicId)

    @GetMapping("/distribution")
    // Возвращает распределение событий по статусам.
    fun distribution() = service.statusDistribution()

    @GetMapping("/sizes")
    // Возвращает размеры таблицы событий и индексов.
    fun sizes() = service.sizes()
}
