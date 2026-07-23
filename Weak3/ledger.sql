-- Неделя 3, шаг 3: ledger как источник истины, balance_minor как projection.
--
-- Файл намеренно НЕ лежит в sql/ и не выполняется автоматически.
-- Сначала запустите queries.sql на пустом ledger и посмотрите на расхождение,
-- потом закройте его этим скриптом:
--
--   docker compose exec -T postgres psql -U study -d fintech -v ON_ERROR_STOP=1 < ledger.sql
--
-- Порядок важен: сначала immutable проводки, потом пересчёт projection из них.
-- Обратный порядок (правим balance, ledger дописываем позже) - это ровно тот
-- баг, из-за которого в проде баланс и история расходятся.

BEGIN;

-- Opening deposit: одна проводка на счёт, датированная регистрацией владельца.
INSERT INTO ledger_entries(account_id, payment_id, amount_minor, created_at)
SELECT a.id, NULL, 10000000, u.created_at
FROM accounts a JOIN users u ON u.id = a.owner_id;

-- Каждый COMPLETED платёж списывает деньги ровно одной проводкой.
-- PENDING и FAILED проводок не создают: деньги двигает не намерение, а результат.
INSERT INTO ledger_entries(account_id, payment_id, amount_minor, created_at)
SELECT p.account_id, p.id, -p.amount_minor, p.created_at
FROM payments p
WHERE p.status = 'COMPLETED';

-- Idempotency key существует для всех платежей, дошедших до обработки.
-- request_hash - это отпечаток payload: тот же key с другим payload должен
-- отклоняться, а не молча возвращать чужой результат.
INSERT INTO idempotency_keys(key, request_hash, payment_id, created_at)
SELECT 'seed-' || p.id,
       md5(p.account_id::text || ':' || p.amount_minor::text),
       p.id,
       p.created_at
FROM payments p
WHERE p.status <> 'FAILED';

-- Projection пересчитывается из ledger, а не наоборот.
UPDATE accounts a
SET balance_minor = t.total
FROM (
    SELECT account_id, sum(amount_minor) AS total
    FROM ledger_entries
    GROUP BY account_id
) t
WHERE t.account_id = a.id;

COMMIT;

-- UPDATE выше создал новую версию каждой строки accounts. Старые версии ещё
-- лежат на страницах: это и есть bloat, ради которого существует VACUUM.
SELECT n_live_tup, n_dead_tup FROM pg_stat_user_tables WHERE relname = 'accounts';
VACUUM (VERBOSE, ANALYZE) accounts;
SELECT n_live_tup, n_dead_tup FROM pg_stat_user_tables WHERE relname = 'accounts';

ANALYZE;

-- Контроль: обе цифры должны быть нулевыми.
SELECT (SELECT count(*) FROM accounts a
        WHERE a.balance_minor <> (SELECT COALESCE(sum(amount_minor), 0)
                                  FROM ledger_entries le WHERE le.account_id = a.id)) AS drifted_accounts,
       (SELECT count(*) FROM payments p
        WHERE p.status = 'COMPLETED'
          AND NOT EXISTS (SELECT 1 FROM ledger_entries le WHERE le.payment_id = p.id)) AS unposted_payments;
