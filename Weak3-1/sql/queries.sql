-- 1. Projection balance сверяется с immutable ledger.
SELECT a.id, a.balance_minor, COALESCE(sum(le.amount_minor), 0) AS ledger_balance
FROM accounts a LEFT JOIN ledger_entries le ON le.account_id = a.id
GROUP BY a.id;

-- 2. Window function строит накопительный оборот.
SELECT account_id, created_at, amount_minor,
       sum(amount_minor) OVER (PARTITION BY account_id ORDER BY created_at, id) AS running_total
FROM ledger_entries;

-- 3. Распределение статусов помогает оценить selectivity будущего индекса.
SELECT status, count(*), round(100.0 * count(*) / sum(count(*)) OVER (), 2) AS percent
FROM payments GROUP BY status ORDER BY count(*) DESC;

-- Продолжите файл минимум до 20 запросов: JOIN, GROUP BY, HAVING, CTE и window functions.
