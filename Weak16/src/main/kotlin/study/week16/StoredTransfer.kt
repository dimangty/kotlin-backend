// Описывает сохранённый перевод и его итоговое состояние.
// Компонент относится к учебному модулю недели 16 и раскрывает его основной пример.
package study.week16

import org.springframework.web.bind.annotation.*
import java.util.UUID

internal data class StoredTransfer(
    val view: TransferView,
    val fromAccountId: UUID,
    val toAccountId: UUID,
    val amountMinor: Long,
)
