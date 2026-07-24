# Code review — Weak5-1

Миграция 24 июля 2026: Spring Boot 4.1.0, Kotlin 2.3.21, Jackson 3 и модульные JDBC/Flyway starters; два Testcontainers-теста проходят на PostgreSQL 17.

Дата: 22 июля 2026. Вердикт: готово, блокирующих замечаний нет.

## Проверено

- `./gradlew test`: 2 Testcontainers integration tests, failures/skips — 0.
- На 20 000 payments history query использует `Index Only Scan` по `payments_user_created_cover_idx`.
- Проверены covering, partial, expression, GIN и BRIN definitions из `pg_indexes`.
- History/pending endpoints имеют bounded limit; `EXPLAIN ANALYZE` выполняет только ограниченный SELECT.
- Compose-файл проходит `docker compose config -q`.

## Исправлено во время review

- Bulk generator получил UUID-based `reference`: повторный вызов больше не конфликтует с UNIQUE.
- `VACUUM (ANALYZE)` оставлен после загрузки осознанно: он обновляет statistics/visibility map для учебного Index Only Scan.
- Testcontainers family выровнена на 2.0.5.

## Сильные стороны

- Порядок equality/range колонок показан рядом с контрпримером обратного порядка.
- Partial predicate записан в запросе явно; covering projection соответствует API.
- Комментарии объясняют не только пользу, но и write/storage cost индексов.

## Учебная работа

Синхронный `VACUUM` в HTTP-запросе — только лабораторный приём, не production pattern. Следующий шаг — сохранить таблицу plan before/after, buffers, размер и цену INSERT для каждого индекса.
