// Описывает учётную запись, используемую в защищённом API.
// Компонент относится к учебному модулю недели 9 и раскрывает его основной пример.
package study.week9

import org.springframework.web.bind.annotation.*
import java.util.UUID

data class Account(val id: UUID, val ownerId: UUID, val balanceMinor: Long)
