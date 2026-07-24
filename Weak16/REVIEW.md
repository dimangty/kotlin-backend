# Review

Миграция 24 июля 2026: Spring Boot 4.1.0, Kotlin 2.3.21, Jackson 3 и модульные JDBC/Flyway starters; четыре Testcontainers-теста проходят на PostgreSQL 17.

Статус: четыре Testcontainers-теста проходят. Два одновременных retry дают один ID; 50 встречных переводов сохраняют total balance и zero-sum ledger; другой payload с тем же key отклоняется; descending cursor не даёт дублей. Добавлен стабильный HTTP error contract и bounded key/actor columns.

Это сильный учебный baseline, но не production-ready: отсутствуют auth/ownership, bounded deadlock/serialization retry, injected rollback test и EXPLAIN-артефакты на миллионе entries.
