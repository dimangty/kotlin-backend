# Review

Миграция 24 июля 2026: Ktor 3.5.1 и Kotlin 2.3.21; оба Testcontainers-теста проходят на PostgreSQL 17.

Статус: Ktor+Hikari+PostgreSQL проходят Testcontainers. Flyway создаёт accounts/transfers/ledger; конкурентный retry возвращает один ID, создаёт один transfer и две zero-sum entries, общий balance сохраняется. Другой payload с тем же key отклоняется.

Исправлены отсутствовавшее сохранение transfer, idempotency, ledger, неиспользуемый Flyway и blocking JDBC на Netty event loop. До полного критерия недели остаются ownership/JWT и deadlock/serialization retry test.
