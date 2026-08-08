-- 1. Обычное ожидание row lock.
BEGIN;
SELECT * FROM accounts
WHERE id = '00000000-0000-0000-0000-000000000001'
FOR UPDATE;
-- Запустите секцию 1 в B: её UPDATE будет ждать.
-- Посмотрите ожидание через locks-inspect.sql и только потом освободите lock.
COMMIT;

-- 2. Deadlock из-за разного порядка A->B и B->A.
BEGIN;
SELECT * FROM accounts WHERE id = '00000000-0000-0000-0000-000000000001' FOR UPDATE;
-- B блокирует второй счёт. После этого выполните следующий SELECT, затем второй SELECT в B.
SELECT * FROM accounts WHERE id = '00000000-0000-0000-0000-000000000002' FOR UPDATE;
-- PostgreSQL обнаружит цикл и откатит одну транзакцию с SQLSTATE 40P01.
COMMIT;

-- 3. Безопасный порядок: обе сессии блокируют UUID по возрастанию.
BEGIN;
SELECT * FROM accounts
WHERE id IN ('00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000002')
ORDER BY id
FOR UPDATE;
UPDATE accounts SET balance_minor = balance_minor - 10 WHERE id = '00000000-0000-0000-0000-000000000001';
UPDATE accounts SET balance_minor = balance_minor + 10 WHERE id = '00000000-0000-0000-0000-000000000002';
COMMIT;
