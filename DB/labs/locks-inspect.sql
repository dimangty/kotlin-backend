-- Повторяйте запрос во время зависания второй сессии.
SELECT pid,
       state,
       wait_event_type,
       wait_event,
       pg_blocking_pids(pid) AS blocked_by,
       now() - xact_start AS transaction_age,
       left(query, 100) AS query
FROM pg_stat_activity
WHERE datname = current_database()
  AND pid <> pg_backend_pid()
ORDER BY xact_start NULLS LAST;

-- granted=false показывает ожидаемые, а не уже полученные блокировки.
SELECT pid, locktype, mode, relation::regclass, page, tuple, granted
FROM pg_locks
WHERE database = (SELECT oid FROM pg_database WHERE datname = current_database())
ORDER BY granted, pid;
