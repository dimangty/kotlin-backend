# Code review — Weak3-1

Миграция 24 июля 2026: Spring Boot 4.1.0, Kotlin 2.3.21, Jackson 3 и модульные JDBC/Flyway starters; Testcontainers-тест проходит на PostgreSQL 17.

Дата: 22 июля 2026. Вердикт: готово, блокирующих замечаний нет.

## Проверено

- `./gradlew test`: 1 Testcontainers integration test, failures/skips — 0.
- Flyway применяет PK, FK, CHECK, NOT NULL и expression UNIQUE на настоящем PostgreSQL 17.
- CRUD-поток создаёт user/account/payment, relational report сверяет stored и ledger balance.
- `ctid`/`xmin` читаются как диагностические, а не бизнес-поля.
- Compose-файл проходит `docker compose config -q`.

## Исправлено во время review

- Агрегации ledger/payments разделены на CTE: прямой JOIN двух one-to-many связей умножал бы суммы.
- Case-insensitive уникальность email перенесена в expression index `lower(email)`, поэтому invariant работает и при прямом SQL.
- Все Testcontainers-зависимости выровнены на 2.0.5 для Docker Engine 29.

## Сильные стороны

- SQL остаётся явным, DTO не маскируют physical model ORM-слоем.
- Комментарии объясняют DB invariants, MVCC-поля и границы применимости.
- Исходная SQL-first лаборатория сохранена рядом с Spring-проектом.

## Учебная работа

Дописать оставшиеся SQL-запросы, загрузить 100 000 строк и сохранить наблюдения по bloat, `VACUUM`, `ANALYZE` и размерам relations.
