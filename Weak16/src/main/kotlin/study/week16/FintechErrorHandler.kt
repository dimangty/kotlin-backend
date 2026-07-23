package study.week16

import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.EmptyResultDataAccessException
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*

@RestControllerAdvice
class FintechErrorHandler {
    @ExceptionHandler(IllegalArgumentException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun invalid(error: IllegalArgumentException) = ApiError("INVALID_REQUEST", error.message ?: "invalid request")

    @ExceptionHandler(EmptyResultDataAccessException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun missing() = ApiError("RESOURCE_NOT_FOUND", "resource not found")

    @ExceptionHandler(IllegalStateException::class, DataIntegrityViolationException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun conflict() = ApiError("OPERATION_REJECTED", "operation violates a business invariant")
}
