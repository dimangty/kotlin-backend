-- Шаги выполняются попеременно с session-b.sql.
BEGIN ISOLATION LEVEL READ COMMITTED;
SELECT balance FROM accounts WHERE id = 1; -- 1000
-- Переключитесь в B, выполните UPDATE+COMMIT, затем продолжите.
SELECT balance FROM accounts WHERE id = 1; -- 900: non-repeatable read
COMMIT;

-- Безопасное atomic update: check и write происходят в одном statement.
UPDATE accounts SET balance = balance - 100 WHERE id = 1 AND balance >= 100 RETURNING *;

