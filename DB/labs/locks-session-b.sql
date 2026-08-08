-- 1. UPDATE ждёт, пока A не сделает COMMIT/ROLLBACK.
BEGIN;
UPDATE accounts SET owner_name = owner_name
WHERE id = '00000000-0000-0000-0000-000000000001';
COMMIT;

-- 2. Захватываем те же строки в обратном порядке и получаем цикл ожидания.
BEGIN;
SELECT * FROM accounts WHERE id = '00000000-0000-0000-0000-000000000002' FOR UPDATE;
-- После попытки A захватить второй счёт выполните:
SELECT * FROM accounts WHERE id = '00000000-0000-0000-0000-000000000001' FOR UPDATE;
COMMIT;

-- 3. Тот же порядок превращает deadlock в обычное ожидание.
BEGIN;
SELECT * FROM accounts
WHERE id IN ('00000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000001')
ORDER BY id
FOR UPDATE;
UPDATE accounts SET balance_minor = balance_minor - 10 WHERE id = '00000000-0000-0000-0000-000000000002';
UPDATE accounts SET balance_minor = balance_minor + 10 WHERE id = '00000000-0000-0000-0000-000000000001';
COMMIT;
