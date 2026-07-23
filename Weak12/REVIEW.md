# Review

Статус: Spring context и HTTP-тесты проходят. Исправлен конфликт имени custom filter со встроенным `requestContextFilter`. Request/operation IDs проходят через MDC/headers, небезопасные client IDs заменяются, timer использует bounded tags, Actuator/Prometheus включены.

Добавлены `compose.yaml` (PostgreSQL 17 с `shared_preload_libraries=pg_stat_statements`, `log_min_duration_statement=200`, `idle_in_transaction_session_timeout=60s`) и `slow-query-lab.sql`: раньше неделя требовала pg_stat_statements и разбор медленного запроса, но базы в проекте не было вообще.

Лаборатория прогнана на миллионе строк: `pg_stat_statements` выводит худший запрос (23.4 мс на вызов, 186 920 буферов), `EXPLAIN (ANALYZE, BUFFERS)` показывает Parallel Seq Scan, covering index даёт 0.056 мс и 1 066 буферов при цене 60 MB индекса на 73 MB heap. Сценарий на две сессии подтверждён отдельно: заблокированный `UPDATE` виден как `wait_event_type = 'Lock'` с корректным `pg_blocking_pids()`, то есть «медленно из-за плана» и «медленно из-за блокировки» различаются по факту, а не по догадке.

Остаются заданиями Hikari/transaction metrics: в приложении недели ещё нет DataSource. Секреты и PII намеренно не логируются.
