// Задаёт представление перевода для ответа API.
// Компонент относится к учебному модулю недели 16 и раскрывает его основной пример.
package study.week16

import org.springframework.web.bind.annotation.*
import java.util.UUID

data class TransferView(val id: UUID, val status: String)
