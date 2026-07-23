package study.week6copy

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class AccountErrorHandler {
    @ExceptionHandler(InsufficientFundsException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun insufficientFunds(error: InsufficientFundsException) = ApiError("INSUFFICIENT_FUNDS", error.message!!)
}
