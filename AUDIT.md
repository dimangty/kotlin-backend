# Аудит учебных проектов по Plan.pdf

Проверено 22 июля 2026 года. План прочитан целиком и визуально проверен по всем 26 страницам. Каталоги `Weak1`...`Weak16` сопоставлены с неделями 1...16; историческое имя `Weak` сохранено, чтобы не ломать пути CI и ссылки.

## Итог

- Все 12 Gradle-проектов собираются; 29 тестов прошли без failures/skips.
- Testcontainers-проекты недель 7, 8, 10, 15 и 16 проверены на PostgreSQL 17.
- SQL недель 3-6 выполнен с `ON_ERROR_STOP=1`: 100 тысяч строк в Week3, по миллиону в Week4/5, setup Week6.
- Week13 собран как реальный Docker image: readiness `200/UP`, user `app`, root filesystem read-only.
- Временные Docker-контейнеры и audit image после проверки удалены.

## Соответствие неделям

| Неделя | Что подтверждено | Что остаётся учебной работой |
|---|---|---|
| 1 | Spring Boot lifecycle, health/hello/echo, HTTP contract tests, JSON Kotlin module | profiles и наблюдение thread-pool saturation |
| 2 | Слои, DTO, validation, optimistic version, единые errors, HTTP-коды 201/200/409/204/404 | pagination и PATCH semantics |
| 3 | Схема/constraints/seed запускаются, 100k payments | ещё 17 SQL-запросов и ledger seed; текущая проверка специально показывает projection 100000 против ledger 0 |
| 4 | 1m events; Seq Scan -> Index Scan, low-cardinality status остаётся Seq Scan | сохранить планы как артефакты и измерить INSERT overhead |
| 5 | 1m payments; covering/partial indexes, Index Only Scan, Heap Fetches 0 | таблица before/after/write price и отдельное измерение write overhead |
| 6 | Пошаговые сценарии non-repeatable read, lost update, atomic fix, RR и Serializable | вручную выполнить две сессии и оформить timelines/retry |
| 7 | Ordered locks, idempotency UNIQUE, ledger, duplicate payload check, 50 parallel transfers | pg_locks/blocker lab и bounded retry 40P01/40001 |
| 8 | Flyway + Hibernate validate + JPA dirty checking + JDBC projection на Testcontainers | controller, N+1 experiment, expand-contract migration |
| 9 | Bcrypt, TTL opaque tokens, refresh rotation, 401/403 ownership tests | PostgreSQL persistence, hashed refresh tokens, logout/revoke-all, audit |
| 10 | Реальный PostgreSQL, UNIQUE и deterministic parallel debit tests | Flyway-on-empty, FK/CHECK mutation tests и serialization retry test |
| 11 | Per-key concurrency, payload binding, cancellation/reconciliation, timeout вне DB transaction | classified retry/backoff и PostgreSQL outbox/state machine |
| 12 | Request/operation IDs, safe MDC, bounded metrics tags, Prometheus, HTTP/metrics tests | Hikari/transaction metrics и pg_stat_statements incident lab |
| 13 | CI, wrapper-based multi-stage image, small Docker context, non-root/read-only/readiness | PostgreSQL migration job, backup/restore и rollback rehearsal |
| 14 | Ktor routing/plugins/serialization/status/auth и negative tests | настоящий JWT/OpenAPI и перенос общей PostgreSQL schema |
| 15 | Ktor + Hikari + Flyway + ordered transaction + idempotency + ledger + Testcontainers; JDBC не блокирует Netty loop | ownership/JWT и deadlock/serialization retry |
| 16 | Vertical transfer slice, audit, error contract, cursor ledger, idempotency, 50 parallel transfers | auth/ownership, bounded retry, injected rollback и EXPLAIN-артефакты |

## Исправленные дефекты

- Добавлен `jackson-module-kotlin` там, где обязательные Kotlin DTO принимались через Spring MVC.
- Week12 больше не конфликтует со встроенным Spring bean `requestContextFilter` и реально запускает context.
- Week11 не перезаписывает успешную операцию поздним concurrent failure и не трактует cancellation как доказанный отказ.
- Week15 теперь сохраняет transfer/ledger, применяет Flyway, защищает idempotency и выполняет blocking JDBC вне Netty event loop.
- Week9 получил достижимый ownership-сценарий, корректный 401 entry point, TTL и безопасный SecurityContext без bearer credentials.
- Week13 получил `.dockerignore` и воспроизводимую сборку через wrapper.
- Week16 получил bounded operation identifiers, стабильный error contract и cursor pagination.

`REVIEW.md` внутри изменённых недель обновлены после фактических прогонов.
