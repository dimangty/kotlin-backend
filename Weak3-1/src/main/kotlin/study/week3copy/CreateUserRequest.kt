// Описывает запрос на регистрацию пользователя.
// Компонент относится к учебному модулю недели 3 и раскрывает его основной пример.
package study.week3copy

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank

data class CreateUserRequest(
    @field:NotBlank @field:Email val email: String,
)
