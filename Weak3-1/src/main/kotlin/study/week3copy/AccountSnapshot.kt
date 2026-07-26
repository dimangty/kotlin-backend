// Представляет снимок состояния счёта вместе с данными о версии строки.
// Компонент относится к учебному модулю недели 3 и раскрывает его основной пример.
package study.week3copy

import java.util.UUID

data class AccountSnapshot(
    val accountId: UUID,
    val storedBalanceMinor: Long,
    val ledgerBalanceMinor: Long,
    val paymentCount: Long,
)
