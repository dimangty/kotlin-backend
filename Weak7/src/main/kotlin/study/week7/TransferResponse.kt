// Задаёт ответ API с результатом перевода.
// Компонент относится к учебному модулю недели 7 и раскрывает его основной пример.
package study.week7

import org.springframework.web.bind.annotation.*
import java.util.UUID

data class TransferResponse(val id: UUID, val status: String)
