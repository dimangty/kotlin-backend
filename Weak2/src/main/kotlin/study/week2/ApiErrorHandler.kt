// Преобразует доменные исключения заметок в ответы HTTP.
// Компонент относится к учебному модулю недели 2 и раскрывает его основной пример.
package study.week2

import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.util.UUID

@RestControllerAdvice
class ApiErrorHandler {
    @ExceptionHandler(NoteNotFound::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    // Преобразует отсутствие заметки в ответ с кодом 404.
    fun missing(error: NoteNotFound, request: HttpServletRequest) = api("NOTE_NOT_FOUND", error.message ?: "Not found", emptyMap(), request)

    @ExceptionHandler(StaleNote::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    // Преобразует конфликт версий заметки в ответ с кодом 409.
    fun stale(error: StaleNote, request: HttpServletRequest) = api("STALE_VERSION", error.message ?: "Conflict", emptyMap(), request)

    @ExceptionHandler(MethodArgumentNotValidException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    // Формирует ответ для ошибок валидации полей запроса.
    fun invalid(error: MethodArgumentNotValidException, request: HttpServletRequest) =
        api("VALIDATION_FAILED", "Request is invalid", error.bindingResult.fieldErrors.associate { it.field to (it.defaultMessage ?: "invalid") }, request)

    @ExceptionHandler(HttpMessageNotReadableException::class, MethodArgumentTypeMismatchException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    // Формирует ответ для нечитаемого тела или параметра неверного типа.
    fun malformed(error: Exception, request: HttpServletRequest) =
        api("MALFORMED_REQUEST", "Request cannot be parsed", emptyMap(), request)

    // Собирает унифицированное представление ошибки API.
    private fun api(code: String, message: String, details: Map<String, String>, request: HttpServletRequest) =
        ApiError(code, message, details, request.getHeader("X-Request-Id") ?: UUID.randomUUID().toString())
}
