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
| 3 | Схема/constraints/seed запускаются, 100k payments, 20 запросов, ledger.sql сводит projection и ledger в ноль | `UNIQUE (payment_id)` и собственный замер bloat |
| 4 | 1m events; Seq Scan -> Index Scan, low-cardinality status остаётся Seq Scan; write overhead измерен (0/1/4 индекса) | сохранить планы как артефакты и повторить UPDATE после fillfactor 70 |
| 5 | 1m payments; covering/partial indexes, Index Only Scan, Heap Fetches 0; порядок колонок, отказ planner от индекса, expression/GIN/BRIN | сводная таблица before/after/write price и эксперимент с падением correlation |
| 6 | Пошаговые сценарии non-repeatable read, lost update, atomic fix, RR и Serializable | вручную выполнить две сессии и оформить timelines/retry |
| 7 | Ordered locks, idempotency UNIQUE, ledger, duplicate payload check, 50 parallel transfers; SQL-лаборатория lock wait/deadlock/SKIP LOCKED и разбор blocker через pg_locks | bounded retry 40P01/40001 в сервисе и worker очереди на SKIP LOCKED |
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

## Доработка 23 июля 2026

Пройден разрыв между планом и материалами по двум главным темам трека. Всё добавленное прогнано на настоящем PostgreSQL 17 с `ON_ERROR_STOP=1`.

- **Неделя 3.** `queries.sql` доведён с трёх запросов до двадцати, разбит на пять блоков. Добавлен `ledger.sql`: immutable проводки, пересчёт projection из них и наглядный цикл dead tuples -> `VACUUM`. Расхождение projection/ledger теперь не просто демонстрируется, но и закрывается.
- **Неделя 4.** Добавлен `write-overhead.sql` - обязательная лабораторная 7 из раздела «Индексы». Измерено: 0/1/4 индекса дают ~130/~240/~1400 мс на INSERT и ~43/~63/~139 MB WAL.
- **Неделя 5.** В `lab.sql` добавлены секции про порядок колонок (7 buffers против 489 на одном и том же запросе) и про осознанный отказ planner от индекса. Добавлен `special-indexes.sql`: expression, GIN и BRIN - типы индексов, которые план требует, а лаборатории раньше не трогали.
- **Неделя 7.** Добавлена SQL-лаборатория блокировок из четырёх файлов. Deadlock, lock wait, единый lock order и `SKIP LOCKED` проверены двумя параллельными сессиями; `locks-inspect.sql` - готовый набор запросов для разбора инцидента.
- **Сквозные треки.** Разделы 3 и 4 плана оформлены как чек-листы: [docs/index-track.md](docs/index-track.md) и [docs/concurrency-track.md](docs/concurrency-track.md). Каждая обязательная лабораторная связана с конкретным файлом репозитория.
- **Инфраструктура.** Все `compose.yaml` принимают `PG_PORT` (и `APP_PORT` для недели 13): раньше любая занятая 5432 на машине ломала запуск недели.

Что осталось учебной работой - перечислено в таблице выше и в `REVIEW.md` каждой недели.

## Доработка 23 июля 2026, вторая итерация

Первая итерация закрыла разрыв по SQL для недель 3-7. Вторая закрывает разрыв в учебных материалах второй половины трека: недели 8-16 имели работающий код, но README на 10-17 строк, из которых нельзя было понять ни теорию недели, ни критерий готовности, ни контрольные вопросы плана.

- **README недель 1, 2 и 8-16 переписаны** по структуре плана: результат недели, таблица «теория - где она в коде», команды запуска с ожидаемым результатом, задания, что разобрать с ментором, критерий готовности, контрольные вопросы, официальные материалы. Каждая ссылка на файл проверена, каждое утверждение о поведении кода сверено с исходником.
- **Неделя 8, `migration-lab.sql`.** Лаборатория миграций, которой в репозитории не было, хотя план требует «миграцию NOT NULL колонки на большой таблице». На 500 000 строк измерено: прямой `SET NOT NULL` - 40 мс под ACCESS EXCLUSIVE, безопасная последовательность `CHECK ... NOT VALID` (0.4 мс) -> `VALIDATE` (17 мс под SHARE UPDATE EXCLUSIVE) -> `SET NOT NULL` (0.4 мс). Дополнительно: `attmissingval` для `ADD COLUMN ... DEFAULT`, три случая `ALTER TYPE` с проверкой rewrite по `relfilenode`, `CREATE INDEX` против `CONCURRENTLY` (107 против 146 мс), бэкофилл батчами с замером мёртвых версий строк, `lock_timeout` как страховка деплоя.
- **Неделя 12, `slow-query-lab.sql` и `compose.yaml`.** Недели 12 не было базы вообще, поэтому пункт плана про `pg_stat_statements` и «найти и исправить один медленный запрос на большой выборке» выполнить было негде. Compose поднимает PostgreSQL с `shared_preload_libraries=pg_stat_statements` и `log_min_duration_statement=200`. На миллионе строк расследование проходит целиком: `pg_stat_statements` находит запрос (23.4 мс на вызов, 186 920 буферов), `EXPLAIN` объясняет причину, covering index даёт 0.056 мс и 1 066 буферов при цене индекса 60 MB, а отдельный сценарий на две сессии показывает разницу между плохим планом и ожиданием блокировки (`wait_event_type = 'Lock'`, `pg_blocking_pids()`).
- **Неделя 13, `docs/safe-deploy.md`.** Ответы на четыре вопроса плана, которых не было ни в одном файле: expand-contract в три релиза, поведение при двух одновременно стартующих instance, правила отката (откатывается приложение, не схема) и процедура backup/restore с проверкой восстановления, а не факта наличия файла. Процедура `pg_dump -Fc` -> `pg_restore` проверена на PostgreSQL 17.

Оба новых SQL-скрипта прогнаны на PostgreSQL 17 с `ON_ERROR_STOP=1`; все числа в README и в этом файле взяты из фактических прогонов, а не оценены. Временные контейнеры удалены.
