-- Неделя 7, третья сессия: диагностика. Здесь не меняются данные, только
-- читается состояние. Открывайте отдельным окном и выполняйте в момент,
-- когда одна из сессий висит.
--
--   docker compose exec postgres psql -U study -d locks -f - < locks-inspect.sql
--
-- В инциденте это и есть первые три запроса, которые нужно выполнить.

-- 1. Кто кого блокирует. pg_blocking_pids даёт готовый ответ и почти всегда
-- заменяет ручной разбор pg_locks.
SELECT a.pid,
       a.state,
       a.wait_event_type,
       a.wait_event,
       pg_blocking_pids(a.pid) AS blocked_by,
       now() - a.xact_start AS xact_age,
       now() - a.query_start AS query_age,
       left(a.query, 60) AS query
FROM pg_stat_activity a
WHERE a.datname = current_database()
  AND a.pid <> pg_backend_pid()
ORDER BY a.xact_start NULLS LAST;

-- 2. Развёрнутая картина: какая транзакция какой lock уже получила,
-- а какая его ждёт. granted = false - это и есть очередь.
SELECT l.pid,
       l.locktype,
       l.mode,
       l.granted,
       CASE WHEN l.relation IS NOT NULL THEN l.relation::regclass::text END AS relation,
       l.transactionid,
       left(a.query, 50) AS query
FROM pg_locks l
LEFT JOIN pg_stat_activity a ON a.pid = l.pid
WHERE l.pid <> pg_backend_pid()
ORDER BY l.granted, l.pid;

-- 3. Пары waiter -> blocker в явном виде: то, что нужно приложить к
-- отчёту по инциденту.
SELECT waiter.pid            AS waiting_pid,
       left(waiter.query, 40) AS waiting_query,
       blocker.pid           AS blocking_pid,
       left(blocker.query, 40) AS blocking_query,
       blocker.state         AS blocking_state,
       now() - blocker.xact_start AS blocker_xact_age
FROM pg_stat_activity waiter
JOIN LATERAL unnest(pg_blocking_pids(waiter.pid)) AS blocking(pid) ON true
JOIN pg_stat_activity blocker ON blocker.pid = blocking.pid
WHERE waiter.datname = current_database();

-- 4. Долгие транзакции опаснее долгих запросов: они держат snapshot и locks,
-- даже когда ничего не делают. idle in transaction в этом списке - находка.
SELECT pid, state, now() - xact_start AS xact_age, left(query, 60) AS last_query
FROM pg_stat_activity
WHERE datname = current_database()
  AND xact_start IS NOT NULL
  AND now() - xact_start > interval '5 seconds'
ORDER BY xact_start;

-- 5. Счётчик deadlock по базе. Растёт после каждого разорванного цикла -
-- удобная метрика для дашборда недели 12. Без принудительного flush
-- значение может отставать и показывать ноль сразу после эксперимента.
SELECT pg_stat_force_next_flush();
SELECT datname, deadlocks, xact_commit, xact_rollback
FROM pg_stat_database WHERE datname = current_database();

-- Как аварийно завершить зависшую сессию (в лаборатории - можно,
-- в production - только осознанно и после понимания причины):
--   SELECT pg_cancel_backend(<pid>);     -- отменить текущий запрос
--   SELECT pg_terminate_backend(<pid>);  -- закрыть соединение целиком
