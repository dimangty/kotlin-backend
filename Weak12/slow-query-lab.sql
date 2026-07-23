-- Неделя 12. Лаборатория диагностики: найти медленный запрос, а не угадать его.
--
-- Запуск:
--   docker compose up -d
--   docker compose exec -T postgres psql -U study -d diag -v ON_ERROR_STOP=1 < slow-query-lab.sql
--
-- Порядок расследования в этой лаборатории тот же, что и в инциденте:
--   1) какой запрос дорогой суммарно (pg_stat_statements),
--   2) почему он дорогой (EXPLAIN ANALYZE, BUFFERS),
--   3) чинится ли это планом или это ожидание блокировки (pg_stat_activity),
--   4) подтвердить исправление теми же метриками, а не ощущением.

\timing on
\set ON_ERROR_STOP on

CREATE EXTENSION IF NOT EXISTS pg_stat_statements;

-- ============================================================
-- Блок 0. Данные размером, на котором план имеет значение
-- ============================================================

DROP TABLE IF EXISTS diag_payments;

CREATE TABLE diag_payments(
    id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_id   bigint NOT NULL,
    amount_minor bigint NOT NULL,
    status       text NOT NULL,
    created_at   timestamptz NOT NULL
);

INSERT INTO diag_payments(account_id, amount_minor, status, created_at)
SELECT
    1 + (i % 20000),
    100 + (i % 90000),
    CASE WHEN i % 50 = 0 THEN 'PENDING' ELSE 'COMPLETED' END,
    now() - make_interval(secs => i % 31536000)
FROM generate_series(1, 1000000) AS s(i);

ANALYZE diag_payments;

-- ============================================================
-- Блок 1. Рабочая нагрузка: три запроса, один из них плохой
-- ============================================================
-- Сбрасываем статистику, чтобы в отчёте были только запросы этой лаборатории.

SELECT pg_stat_statements_reset();

-- 1.1 Дешёвый запрос по первичному ключу, выполняется часто.
DO $$
DECLARE dummy record;
BEGIN
    FOR i IN 1..200 LOOP
        SELECT * INTO dummy FROM diag_payments WHERE id = 1 + (i * 4177) % 1000000;
    END LOOP;
END
$$;

-- 1.2 История по счёту: индекса нет, каждый вызов читает миллион строк.
DO $$
DECLARE dummy record;
BEGIN
    FOR i IN 1..20 LOOP
        SELECT count(*), sum(amount_minor) INTO dummy
        FROM diag_payments
        WHERE account_id = 1 + (i * 977) % 20000
          AND created_at > now() - interval '90 days';
    END LOOP;
END
$$;

-- 1.3 Агрегат за год: читает всю таблицу осознанно, и это нормально.
DO $$
DECLARE dummy record;
BEGIN
    FOR i IN 1..3 LOOP
        SELECT count(*) INTO dummy FROM diag_payments WHERE status = 'COMPLETED';
    END LOOP;
END
$$;

-- ============================================================
-- Блок 2. Кто виноват: сортировка по общему времени, а не по одному вызову
-- ============================================================
-- Дорогой запрос - не всегда самый медленный. Запрос на 5 мс, вызванный
-- миллион раз, съедает больше, чем отчёт на 3 секунды раз в сутки.
-- Поэтому смотрят и total_exec_time, и mean_exec_time.

SELECT
    round(total_exec_time::numeric, 1) AS total_ms,
    calls,
    round(mean_exec_time::numeric, 2)  AS mean_ms,
    rows,
    shared_blks_hit + shared_blks_read AS blocks,
    left(regexp_replace(query, '\s+', ' ', 'g'), 70) AS query
FROM pg_stat_statements
WHERE query ILIKE '%diag_payments%'
  AND query NOT ILIKE '%pg_stat_statements%'
ORDER BY total_exec_time DESC
LIMIT 5;

-- ============================================================
-- Блок 3. Почему он дорогой: план и buffers
-- ============================================================
-- Сравнивать планы нужно по Buffers, а не по времени: время зависит от того,
-- что уже лежит в page cache, а buffers - это фактическая работа.

EXPLAIN (ANALYZE, BUFFERS)
SELECT count(*), sum(amount_minor)
FROM diag_payments
WHERE account_id = 4242
  AND created_at > now() - interval '90 days';

-- ============================================================
-- Блок 4. Исправление и подтверждение
-- ============================================================

CREATE INDEX diag_payments_account_created_idx
    ON diag_payments(account_id, created_at DESC)
    INCLUDE (amount_minor);
ANALYZE diag_payments;

EXPLAIN (ANALYZE, BUFFERS)
SELECT count(*), sum(amount_minor)
FROM diag_payments
WHERE account_id = 4242
  AND created_at > now() - interval '90 days';

-- Та же нагрузка после индекса: mean_ms должен упасть на порядки.
SELECT pg_stat_statements_reset();

DO $$
DECLARE dummy record;
BEGIN
    FOR i IN 1..20 LOOP
        SELECT count(*), sum(amount_minor) INTO dummy
        FROM diag_payments
        WHERE account_id = 1 + (i * 977) % 20000
          AND created_at > now() - interval '90 days';
    END LOOP;
END
$$;

SELECT
    calls,
    round(mean_exec_time::numeric, 3) AS mean_ms,
    shared_blks_hit + shared_blks_read AS blocks,
    left(regexp_replace(query, '\s+', ' ', 'g'), 70) AS query
FROM pg_stat_statements
WHERE query ILIKE '%diag_payments%'
  AND query ILIKE '%account_id%'
  AND query NOT ILIKE '%pg_stat_statements%'
ORDER BY total_exec_time DESC
LIMIT 3;

-- Цена решения: сколько места занял индекс.
SELECT
    pg_size_pretty(pg_relation_size('diag_payments'))  AS heap,
    pg_size_pretty(pg_indexes_size('diag_payments'))   AS indexes;

-- ============================================================
-- Блок 5. Плохой план или ожидание блокировки: как различить
-- ============================================================
-- pg_stat_statements показывает суммарное время, в которое входит и ожидание
-- блокировки. Запрос с идеальным планом может стоять в очереди минутами.
-- Различие видно только в pg_stat_activity: wait_event_type = 'Lock'.
--
-- Чтобы увидеть это вживую, откройте второе окно psql и выполните:
--
--   BEGIN;
--   UPDATE diag_payments SET status = 'PENDING' WHERE id = 1;
--   -- транзакцию не завершайте
--
-- затем третье окно:
--
--   UPDATE diag_payments SET status = 'FAILED' WHERE id = 1;   -- повиснет
--
-- и запустите следующий запрос: он ответит, кто кого держит.

SELECT
    a.pid,
    a.state,
    a.wait_event_type,
    a.wait_event,
    pg_blocking_pids(a.pid) AS blocked_by,
    round(extract(epoch FROM now() - a.xact_start)::numeric, 1) AS xact_age_s,
    left(regexp_replace(a.query, '\s+', ' ', 'g'), 60) AS query
FROM pg_stat_activity a
WHERE a.datname = current_database()
  AND a.pid <> pg_backend_pid()
ORDER BY a.xact_start NULLS LAST;

-- ============================================================
-- Блок 6. Чеклист инцидента "стало медленно"
-- ============================================================
-- Эти четыре запроса стоит держать под рукой: они закрывают большинство
-- инцидентов на одном сервисе с одной базой.

-- 6.1 Самые дорогие запросы суммарно.
SELECT round(total_exec_time::numeric) AS total_ms, calls,
       left(regexp_replace(query, '\s+', ' ', 'g'), 60) AS query
FROM pg_stat_statements ORDER BY total_exec_time DESC LIMIT 5;

-- 6.2 Долгие транзакции и брошенные сессии: самая частая причина роста bloat
--     и блокировок. 'idle in transaction' - всегда красный флаг.
SELECT pid, state, round(extract(epoch FROM now() - xact_start)::numeric, 1) AS xact_age_s
FROM pg_stat_activity
WHERE datname = current_database() AND xact_start IS NOT NULL AND pid <> pg_backend_pid()
ORDER BY xact_start;

-- 6.3 Счётчики базы: deadlocks и conflicts растут - значит, проблема в
--     конкурентности, а не в планах.
SELECT numbackends, xact_commit, xact_rollback, deadlocks, blks_hit, blks_read,
       round(100.0 * blks_hit / nullif(blks_hit + blks_read, 0), 2) AS cache_hit_pct
FROM pg_stat_database
WHERE datname = current_database();

-- 6.4 Таблицы, где Seq Scan побеждает Index Scan: кандидаты на разбор планов.
SELECT relname, seq_scan, seq_tup_read, idx_scan, n_live_tup, n_dead_tup
FROM pg_stat_user_tables
WHERE seq_scan > 0
ORDER BY seq_tup_read DESC
LIMIT 5;

\timing off
