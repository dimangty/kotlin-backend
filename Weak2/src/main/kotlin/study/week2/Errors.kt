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

data class ApiError(val code: String, val message: String, val details: Map<String, String>, val requestId: String)

@RestControllerAdvice
class ApiErrorHandler {
    @ExceptionHandler(NoteNotFound::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun missing(error: NoteNotFound, request: HttpServletRequest) = api("NOTE_NOT_FOUND", error.message ?: "Not found", emptyMap(), request)

    @ExceptionHandler(StaleNote::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun stale(error: StaleNote, request: HttpServletRequest) = api("STALE_VERSION", error.message ?: "Conflict", emptyMap(), request)

    @ExceptionHandler(MethodArgumentNotValidException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun invalid(error: MethodArgumentNotValidException, request: HttpServletRequest) =
        api("VALIDATION_FAILED", "Request is invalid", error.bindingResult.fieldErrors.associate { it.field to (it.defaultMessage ?: "invalid") }, request)

    @ExceptionHandler(HttpMessageNotReadableException::class, MethodArgumentTypeMismatchException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun malformed(error: Exception, request: HttpServletRequest) =
        api("MALFORMED_REQUEST", "Request cannot be parsed", emptyMap(), request)

    private fun api(code: String, message: String, details: Map<String, String>, request: HttpServletRequest) =
        ApiError(code, message, details, request.getHeader("X-Request-Id") ?: UUID.randomUUID().toString())
}
