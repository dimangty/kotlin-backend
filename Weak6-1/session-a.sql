-- Выполняйте секции синхронно с одноимёнными секциями session-b.sql.
-- Reset запускайте только при отсутствии открытой transaction в session B.

-- 1. Non-repeatable read в READ COMMITTED.
UPDATE accounts SET balance = 1000;
BEGIN ISOLATION LEVEL READ COMMITTED;
SELECT balance FROM accounts WHERE id = 1; -- 1000
-- B: выполните секцию 1 целиком.
SELECT balance FROM accounts WHERE id = 1; -- 900
COMMIT;

-- 2. Lost update через read-compute-write.
UPDATE accounts SET balance = 1000;
BEGIN ISOLATION LEVEL READ COMMITTED;
SELECT balance FROM accounts WHERE id = 1; -- оба клиента вычислят 1000 - 100 = 900
-- B: BEGIN и SELECT из секции 2.
UPDATE accounts SET balance = 900 WHERE id = 1;
COMMIT;
-- B: UPDATE 900 и COMMIT. Итог 900 вместо ожидаемых 800.
SELECT balance FROM accounts WHERE id = 1;

-- 3. Безопасный atomic update: check и write находятся в одном statement.
UPDATE accounts SET balance = 1000;
UPDATE accounts
SET balance = balance - 100
WHERE id = 1 AND balance >= 100
RETURNING balance;
-- B: выполните atomic UPDATE из секции 3; итог должен быть 800.
SELECT balance FROM accounts WHERE id = 1;

-- 4. Стабильный snapshot в REPEATABLE READ.
UPDATE accounts SET balance = 1000;
BEGIN ISOLATION LEVEL REPEATABLE READ;
SELECT balance FROM accounts WHERE id = 1; -- 1000
-- B: выполните секцию 4 целиком.
SELECT balance FROM accounts WHERE id = 1; -- всё ещё 1000 в snapshot A
COMMIT;
SELECT balance FROM accounts WHERE id = 1; -- 900 в новой transaction

-- 5. Serialization failure: обе transaction читают общий invariant и пишут разные rows.
UPDATE accounts SET balance = 1000;
BEGIN ISOLATION LEVEL SERIALIZABLE;
SELECT sum(balance) FROM accounts; -- 2000
-- B: BEGIN SERIALIZABLE и SELECT sum из секции 5.
UPDATE accounts SET balance = balance + 1 WHERE id = 1;
-- B: UPDATE id=2, затем вернитесь сюда.
COMMIT;
-- B: COMMIT; одна из двух transaction должна получить SQLSTATE 40001.
