// Преобразует ошибки финансового домена в ответы HTTP.
// Компонент относится к учебному модулю недели 16 и раскрывает его основной пример.
package study.week16

import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.EmptyResultDataAccessException
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*

@RestControllerAdvice
class FintechErrorHandler {
    @ExceptionHandler(IllegalArgumentException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    // Преобразует некорректный запрос в ответ с кодом 400.
    fun invalid(error: IllegalArgumentException) = ApiError("INVALID_REQUEST", error.message ?: "invalid request")

    @ExceptionHandler(EmptyResultDataAccessException::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    // Преобразует отсутствие ресурса в ответ с кодом 404.
    fun missing() = ApiError("RESOURCE_NOT_FOUND", "resource not found")

    @ExceptionHandler(IllegalStateException::class, DataIntegrityViolationException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    // Преобразует нарушение бизнес-инварианта в ответ с кодом 409.
    fun conflict() = ApiError("OPERATION_REJECTED", "operation violates a business invariant")
}
