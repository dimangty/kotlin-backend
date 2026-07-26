// Описывает входные данные денежного перевода.
// Компонент относится к учебному модулю недели 7 и раскрывает его основной пример.
package study.week7

import org.springframework.web.bind.annotation.*
import java.util.UUID

data class TransferRequest(val fromAccountId: UUID, val toAccountId: UUID, val amountMinor: Long)
