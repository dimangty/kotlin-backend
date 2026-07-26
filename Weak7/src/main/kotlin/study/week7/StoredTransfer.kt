// Описывает сохранённый перевод и его итоговое состояние.
// Компонент относится к учебному модулю недели 7 и раскрывает его основной пример.
package study.week7

import org.springframework.web.bind.annotation.*
import java.util.UUID

internal data class StoredTransfer(
    val response: TransferResponse,
    val fromAccountId: UUID,
    val toAccountId: UUID,
    val amountMinor: Long,
)
