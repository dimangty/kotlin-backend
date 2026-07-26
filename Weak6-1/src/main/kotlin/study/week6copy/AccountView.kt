// Задаёт внешнее представление счёта для ответа API.
// Компонент относится к учебному модулю недели 6 и раскрывает его основной пример.
package study.week6copy

import java.util.UUID

data class AccountView(
    val id: UUID,
    val balanceMinor: Long,
    val strategy: String,
    val attempts: Int = 1,
)
