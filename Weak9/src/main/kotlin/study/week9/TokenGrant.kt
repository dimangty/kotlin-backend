// Описывает пару токенов, выданную после успешной аутентификации.
// Компонент относится к учебному модулю недели 9 и раскрывает его основной пример.
package study.week9

import org.springframework.web.bind.annotation.*
import java.time.Instant
import java.util.UUID

internal data class TokenGrant(val userId: UUID, val expiresAt: Instant)
