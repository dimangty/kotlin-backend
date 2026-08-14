package ru.dbappweb.backend.api

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.servlet.resource.NoResourceFoundException
import ru.dbapp.data.PostgresScenarioService
import ru.dbapp.model.ConnectionInfo
import ru.dbapp.model.DemoCatalog
import ru.dbapp.model.DemoTopic
import ru.dbapp.model.ParallelParticipantReport
import ru.dbappweb.backend.parallel.ParallelScenarioCoordinator

/** REST API предоставляет состояние, каталог и синхронизированные участники учебного запуска. */
@RestController
@RequestMapping("/api")
class DemoController(
    private val service: PostgresScenarioService,
    private val coordinator: ParallelScenarioCoordinator,
) {
    /** Статус выполняет настоящий JDBC-запрос, а не возвращает формальный HTTP 200. */
    @GetMapping("/status")
    fun status(): ConnectionInfo = service.connectionInfo()

    /** Каталог полезен для ручной проверки API и будущих клиентов, хотя commonMain хранит локальную копию. */
    @GetMapping("/catalog")
    fun catalog(): List<DemoTopic> = DemoCatalog.topics

    /** Два POST с общим runId приходят параллельно и встречаются на серверном барьере. */
    @PostMapping("/examples/{exampleId}/parallel-runs/{runId}/participants/{participant}")
    fun runParticipant(
        @PathVariable exampleId: String,
        @PathVariable runId: String,
        @PathVariable participant: Int,
        @RequestParam participantCount: Int,
    ): ParallelParticipantReport = coordinator.runParticipant(
        exampleId = exampleId,
        runId = runId,
        participant = participant,
        participantCount = participantCount,
    )
}

/** Структурированная ошибка даёт клиенту понятное русское сообщение вместо HTML-страницы Tomcat. */
data class ApiError(val message: String)

/** Ошибки маршрутизации отделены от недоступной базы и внутренних сбоев API. */
@RestControllerAdvice
class DemoExceptionHandler {
    /** Неизвестный идентификатор является ошибкой клиента и не должен выглядеть как сбой PostgreSQL. */
    @ExceptionHandler(NoSuchElementException::class)
    fun notFound(error: NoSuchElementException): ResponseEntity<ApiError> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiError(error.message ?: "Пример не найден"))

    /** Некорректные runId и участники возвращаются как HTTP 400 до создания серверного барьера. */
    @ExceptionHandler(IllegalArgumentException::class)
    fun badRequest(error: IllegalArgumentException): ResponseEntity<ApiError> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiError(error.message ?: "Некорректный parallel-run"))

    /** Удалённый последовательный маршрут и любые неизвестные API-пути должны оставаться HTTP 404. */
    @ExceptionHandler(NoResourceFoundException::class)
    fun routeNotFound(error: NoResourceFoundException): ResponseEntity<ApiError> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiError("Маршрут ${error.resourcePath} не найден"))

    /** Ошибка status endpoint чаще всего означает, что контейнер PostgreSQL ещё не готов. */
    @ExceptionHandler(Exception::class)
    fun unavailable(error: Exception): ResponseEntity<ApiError> =
        ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(ApiError(error.message ?: "Бэкенд временно недоступен"))
}
