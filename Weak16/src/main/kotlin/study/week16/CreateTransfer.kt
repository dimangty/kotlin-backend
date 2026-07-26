// Описывает команду создания перевода между счетами.
// Компонент относится к учебному модулю недели 16 и раскрывает его основной пример.
package study.week16

import org.springframework.web.bind.annotation.*
import java.util.UUID

data class CreateTransfer(val fromAccountId: UUID, val toAccountId: UUID, val amountMinor: Long)
