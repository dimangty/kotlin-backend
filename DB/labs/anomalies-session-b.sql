-- Команды второй сессии соответствуют одноимённым секциям session A.

-- 1. Оставьте транзакцию открытой, вернитесь в A, затем выполните ROLLBACK.
BEGIN;
UPDATE accounts SET balance_minor = 900 WHERE id = '00000000-0000-0000-0000-000000000001';
ROLLBACK;

-- 2. Изменение между двумя SELECT уровня Read Committed.
UPDATE accounts SET balance_minor = balance_minor + 100
WHERE id = '00000000-0000-0000-0000-000000000001';

-- 3. Изменение не попадёт в уже созданный Repeatable Read snapshot сессии A.
UPDATE accounts SET balance_minor = balance_minor + 100
WHERE id = '00000000-0000-0000-0000-000000000001';

-- 4. Новая подходящая строка создаёт phantom для Read Committed.
INSERT INTO payments(user_id, reference, status, amount_minor, created_at)
VALUES (42, 'PAY-PHANTOM-' || gen_random_uuid(), 'COMPLETED', 300, now());

-- 5. Выполните BEGIN и SELECT, вернитесь в A для UPDATE/COMMIT, затем продолжите здесь.
BEGIN ISOLATION LEVEL READ COMMITTED;
SELECT balance_minor FROM accounts WHERE id = '00000000-0000-0000-0000-000000000001';
UPDATE accounts SET balance_minor = 1200 WHERE id = '00000000-0000-0000-0000-000000000001';
COMMIT;

-- 6. Выполните BEGIN и SELECT, дайте A сделать UPDATE/COMMIT, затем продолжите здесь.
BEGIN ISOLATION LEVEL SERIALIZABLE;
SELECT balance_minor FROM accounts WHERE id = '00000000-0000-0000-0000-000000000001';
UPDATE accounts SET balance_minor = 1200 WHERE id = '00000000-0000-0000-0000-000000000001';
COMMIT;
