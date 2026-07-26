// Описывает физическую версию строки PostgreSQL для учебной диагностики.
// Компонент относится к учебному модулю недели 3 и раскрывает его основной пример.
package study.week3copy

import java.util.UUID

data class PhysicalTuple(val accountId: UUID, val ctid: String, val xmin: Long)
