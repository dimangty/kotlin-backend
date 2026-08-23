package study.week10

import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.util.UUID

@RestControllerAdvice
class ApiErrorHandler {
    private val logger = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(InstructorNotFound::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun instructorNotFound(error: InstructorNotFound, request: HttpServletRequest) =
        response("INSTRUCTOR_NOT_FOUND", error.message ?: "Instructor not found", request = request)

    @ExceptionHandler(CourseNotFound::class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    fun courseNotFound(error: CourseNotFound, request: HttpServletRequest) =
        response("COURSE_NOT_FOUND", error.message ?: "Course not found", request = request)

    @ExceptionHandler(MethodArgumentNotValidException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun invalid(error: MethodArgumentNotValidException, request: HttpServletRequest): ApiError {
        val details = error.bindingResult.fieldErrors
            .sortedBy { it.field }
            .associate { it.field to (it.defaultMessage ?: "invalid") }
        return response("VALIDATION_FAILED", "Request is invalid", details, request)
    }

    @ExceptionHandler(HttpMessageNotReadableException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun malformed(request: HttpServletRequest) =
        response("MALFORMED_REQUEST", "Request body cannot be parsed", request = request)

    @ExceptionHandler(Exception::class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    fun unexpected(error: Exception, request: HttpServletRequest): ApiError {
        logger.error("Unexpected request failure", error)
        return response("INTERNAL_ERROR", "Unexpected error", request = request)
    }

    private fun response(
        code: String,
        message: String,
        details: Map<String, String> = emptyMap(),
        request: HttpServletRequest,
    ) = ApiError(
        code = code,
        message = message,
        details = details,
        requestId = request.getHeader("X-Request-Id") ?: UUID.randomUUID().toString(),
    )
}
