-- Выполняйте команды только после соответствующей подсказки в session-a.sql.

-- 1. Изменение между двумя SELECT session A.
BEGIN;
UPDATE accounts SET balance = balance - 100 WHERE id = 1;
COMMIT;

-- 2. Оба клиента читают старое значение, затем записывают одинаковый computed result.
BEGIN ISOLATION LEVEL READ COMMITTED;
SELECT balance FROM accounts WHERE id = 1; -- 1000; затем A пишет 900 и COMMIT
UPDATE accounts SET balance = 900 WHERE id = 1;
COMMIT;

-- 3. Второй atomic debit не теряется.
UPDATE accounts
SET balance = balance - 100
WHERE id = 1 AND balance >= 100
RETURNING balance; -- 800

-- 4. UPDATE не меняет snapshot уже открытой REPEATABLE READ transaction A.
BEGIN;
UPDATE accounts SET balance = balance - 100 WHERE id = 1;
COMMIT;

-- 5. Конфликт Serializable. Начните после первого SELECT sum в session A.
BEGIN ISOLATION LEVEL SERIALIZABLE;
SELECT sum(balance) FROM accounts; -- 2000
-- A: UPDATE id=1.
UPDATE accounts SET balance = balance + 1 WHERE id = 2;
-- A: COMMIT, затем COMMIT здесь. Ожидается SQLSTATE 40001 у одной transaction.
COMMIT;
