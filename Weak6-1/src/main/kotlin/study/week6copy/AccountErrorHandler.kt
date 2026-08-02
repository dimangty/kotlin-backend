// Преобразует ошибки операций со счётом в единообразные HTTP-ответы.
// Компонент относится к учебному модулю недели 6 и раскрывает его основной пример.
package study.week6copy

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class AccountErrorHandler {
    @ExceptionHandler(InsufficientFundsException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    // Преобразует ошибку недостатка средств в ответ API.
    fun insufficientFunds(error: InsufficientFundsException) = ApiError("INSUFFICIENT_FUNDS", error.message!!)
}
