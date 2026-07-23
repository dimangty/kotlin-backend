-- Неделя 3: 20 запросов к финтех-схеме без ORM.
--
-- Файл лежит в sql/ и поэтому выполняется автоматически при первом
-- `docker compose up`. В этот момент ledger_entries ещё пуст: запросы 1-3
-- специально показывают расхождение projection и ledger. После ручного
-- запуска ../ledger.sql выполните файл повторно и сравните результаты:
--
--   docker compose exec -T postgres psql -U study -d fintech \
--     -v ON_ERROR_STOP=1 -f /docker-entrypoint-initdb.d/queries.sql
--
-- Блок A (1-3)   - согласованность денег.
-- Блок B (4-9)   - JOIN, GROUP BY, HAVING, агрегаты.
-- Блок C (10-13) - CTE и window functions.
-- Блок D (14-17) - физическое хранение: tuples, ctid, xmin, размер.
-- Блок E (18-20) - инварианты, которые обязана держать сама база.

-- ============================ A. Деньги ============================

-- 1. Projection balance сверяется с immutable ledger.
SELECT a.id, a.balance_minor, COALESCE(sum(le.amount_minor), 0) AS ledger_balance
FROM accounts a LEFT JOIN ledger_entries le ON le.account_id = a.id
GROUP BY a.id
LIMIT 20;

-- 2. Тот же reconcile, но возвращает только расхождения.
-- До ledger.sql запрос вернёт все счета, после - ноль строк.
SELECT a.id,
       a.balance_minor,
       COALESCE(sum(le.amount_minor), 0) AS ledger_balance,
       a.balance_minor - COALESCE(sum(le.amount_minor), 0) AS drift_minor
FROM accounts a LEFT JOIN ledger_entries le ON le.account_id = a.id
GROUP BY a.id, a.balance_minor
HAVING a.balance_minor <> COALESCE(sum(le.amount_minor), 0)
ORDER BY abs(a.balance_minor - COALESCE(sum(le.amount_minor), 0)) DESC
LIMIT 20;

-- 3. COMPLETED платёж без проводки - это потерянные деньги.
-- На здоровой базе (после ledger.sql) запрос возвращает ноль строк.
SELECT p.id, p.account_id, p.amount_minor, p.created_at
FROM payments p
WHERE p.status = 'COMPLETED'
  AND NOT EXISTS (SELECT 1 FROM ledger_entries le WHERE le.payment_id = p.id)
LIMIT 20;

-- ====================== B. JOIN, GROUP BY, HAVING ======================

-- 4. Распределение статусов: основа для оценки selectivity будущего индекса.
SELECT status, count(*), round(100.0 * count(*) / sum(count(*)) OVER (), 2) AS percent
FROM payments GROUP BY status ORDER BY count(*) DESC;

-- 5. INNER JOIN трёх таблиц: пользователь, его счёт и последние платежи.
SELECT u.email, a.currency, p.amount_minor, p.status, p.created_at
FROM users u
JOIN accounts a ON a.owner_id = u.id
JOIN payments p ON p.account_id = a.id
ORDER BY p.created_at DESC
LIMIT 20;

-- 6. LEFT JOIN находит счета вообще без платежей.
-- Отличие от INNER JOIN здесь принципиально: INNER такие строки скрывает.
SELECT a.id, u.email
FROM accounts a
JOIN users u ON u.id = a.owner_id
LEFT JOIN payments p ON p.account_id = a.id
WHERE p.id IS NULL
LIMIT 20;

-- 7. Агрегаты по счетам с FILTER вместо трёх отдельных запросов.
SELECT a.id,
       count(*) AS payments_total,
       count(*) FILTER (WHERE p.status = 'COMPLETED') AS completed,
       count(*) FILTER (WHERE p.status = 'PENDING')   AS pending,
       count(*) FILTER (WHERE p.status = 'FAILED')    AS failed,
       sum(p.amount_minor) FILTER (WHERE p.status = 'COMPLETED') AS completed_minor
FROM accounts a JOIN payments p ON p.account_id = a.id
GROUP BY a.id
ORDER BY completed_minor DESC NULLS LAST
LIMIT 20;

-- 8. HAVING отбирает группы после агрегации: счета с крупным оборотом.
SELECT p.account_id,
       count(*) AS completed_count,
       sum(p.amount_minor) AS turnover_minor
FROM payments p
WHERE p.status = 'COMPLETED'
GROUP BY p.account_id
HAVING sum(p.amount_minor) > 2000000
ORDER BY turnover_minor DESC
LIMIT 20;

-- 9. Помесячная динамика: date_trunc как группирующее выражение.
SELECT date_trunc('month', created_at) AS month,
       count(*),
       sum(amount_minor) FILTER (WHERE status = 'COMPLETED') AS completed_minor
FROM payments
GROUP BY 1
ORDER BY 1;

-- ====================== C. CTE и window functions ======================

-- 10. Накопительный оборот по счёту. ORDER BY внутри окна задаёт
-- детерминированный порядок: created_at может повторяться, id - нет.
SELECT account_id, created_at, amount_minor,
       sum(amount_minor) OVER (PARTITION BY account_id ORDER BY created_at, id) AS running_total
FROM ledger_entries
ORDER BY account_id, created_at, id
LIMIT 50;

-- 11. ROW_NUMBER выбирает последний платёж каждого счёта (top-1-per-group).
WITH ranked AS (
    SELECT p.*, row_number() OVER (PARTITION BY p.account_id ORDER BY p.created_at DESC, p.id DESC) AS rn
    FROM payments p
)
SELECT account_id, id, amount_minor, status, created_at
FROM ranked WHERE rn = 1
ORDER BY created_at DESC
LIMIT 20;

-- 12. LAG измеряет паузу между соседними платежами счёта.
SELECT account_id, created_at,
       created_at - lag(created_at) OVER (PARTITION BY account_id ORDER BY created_at) AS gap
FROM payments
ORDER BY account_id, created_at
LIMIT 50;

-- 13. Многошаговый CTE: доля счёта в общем обороте.
WITH per_account AS (
    SELECT account_id, sum(amount_minor) AS turnover
    FROM payments WHERE status = 'COMPLETED'
    GROUP BY account_id
), total AS (
    SELECT sum(turnover) AS all_turnover FROM per_account
)
SELECT pa.account_id, pa.turnover,
       round(100.0 * pa.turnover / t.all_turnover, 4) AS percent_of_total
FROM per_account pa CROSS JOIN total t
ORDER BY pa.turnover DESC
LIMIT 20;

-- ====================== D. Физическое хранение ======================

-- 14. Логическая строка и физический tuple. ctid = (страница, номер в странице),
-- xmin = транзакция, создавшая эту версию строки.
SELECT ctid, xmin, xmax, id, status, amount_minor
FROM payments
ORDER BY ctid
LIMIT 10;

-- 15. Сколько версий строк лежит на одной heap page.
SELECT (ctid::text::point)[0]::bigint AS page, count(*) AS tuples
FROM payments
GROUP BY 1 ORDER BY 1
LIMIT 10;

-- 16. Размеры отношений: heap, индексы и итог.
SELECT relname,
       pg_size_pretty(pg_relation_size(oid))       AS heap,
       pg_size_pretty(pg_indexes_size(oid))        AS indexes,
       pg_size_pretty(pg_total_relation_size(oid)) AS total
FROM pg_class
WHERE relkind = 'r' AND relnamespace = 'public'::regnamespace
ORDER BY pg_total_relation_size(oid) DESC;

-- 17. Статистика планировщика и следы автовакуума.
-- n_dead_tup растёт после UPDATE/DELETE: это и есть будущий bloat.
SELECT relname, n_live_tup, n_dead_tup, last_analyze, last_autoanalyze, last_autovacuum
FROM pg_stat_user_tables ORDER BY relname;

-- ====================== E. Инварианты уровня базы ======================

-- 18. Ссылочная целостность: платёж без счёта невозможен, потому что это
-- запрещает FOREIGN KEY, а не код приложения. Ожидается 0.
SELECT count(*) AS orphan_payments
FROM payments p LEFT JOIN accounts a ON a.id = p.account_id
WHERE a.id IS NULL;

-- 19. Все constraints схемы одним списком - это и есть контракт данных.
SELECT rel.relname AS table_name, con.conname, con.contype,
       pg_get_constraintdef(con.oid) AS definition
FROM pg_constraint con
JOIN pg_class rel ON rel.oid = con.conrelid
WHERE rel.relnamespace = 'public'::regnamespace
ORDER BY rel.relname, con.contype, con.conname;

-- 20. На один платёж не может ссылаться два idempotency key.
-- Запрос должен вернуть ноль строк; это же правило стоит закрепить
-- отдельным UNIQUE(payment_id), а не надеяться на аккуратность кода.
SELECT payment_id, count(*) AS keys_pointing_here
FROM idempotency_keys
WHERE payment_id IS NOT NULL
GROUP BY payment_id
HAVING count(*) > 1;
