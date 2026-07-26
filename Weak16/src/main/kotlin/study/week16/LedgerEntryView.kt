// Представляет запись журнала движения средств в ответе API.
// Компонент относится к учебному модулю недели 16 и раскрывает его основной пример.
package study.week16

import org.springframework.web.bind.annotation.*
import java.util.UUID

data class LedgerEntryView(val id: Long, val transferId: UUID, val amountMinor: Long)
