-- Выполняйте по одной секции, переключаясь на session B в местах с подсказкой.

-- 1. Dirty read отсутствует даже на минимальном уровне PostgreSQL.
BEGIN ISOLATION LEVEL READ COMMITTED;
SELECT balance_minor FROM accounts WHERE id = '00000000-0000-0000-0000-000000000001';
-- Теперь выполните секцию 1 в session B, но не делайте COMMIT.
SELECT balance_minor FROM accounts WHERE id = '00000000-0000-0000-0000-000000000001';
-- Результат всё ещё 1000: незакоммиченное значение 900 не видно.
COMMIT;
-- Попросите session B выполнить ROLLBACK.

-- 2. Non-repeatable read в Read Committed.
BEGIN ISOLATION LEVEL READ COMMITTED;
SELECT balance_minor FROM accounts WHERE id = '00000000-0000-0000-0000-000000000001';
-- Выполните секцию 2 в session B с COMMIT.
SELECT balance_minor FROM accounts WHERE id = '00000000-0000-0000-0000-000000000001';
-- Второй SELECT видит новое committed-значение.
COMMIT;

-- 3. Repeatable Read сохраняет snapshot.
BEGIN ISOLATION LEVEL REPEATABLE READ;
SELECT balance_minor FROM accounts WHERE id = '00000000-0000-0000-0000-000000000001';
-- Выполните секцию 3 в session B с COMMIT.
SELECT balance_minor FROM accounts WHERE id = '00000000-0000-0000-0000-000000000001';
-- Внутри этой транзакции значение прежнее; после COMMIT следующий SELECT увидит новое.
COMMIT;
SELECT balance_minor FROM accounts WHERE id = '00000000-0000-0000-0000-000000000001';

-- 4. Phantom read в Read Committed.
BEGIN ISOLATION LEVEL READ COMMITTED;
SELECT count(*) FROM payments WHERE user_id = 42;
-- Выполните секцию 4 в session B с INSERT и COMMIT.
SELECT count(*) FROM payments WHERE user_id = 42;
-- Количество изменилось внутри одной транзакции: появилась строка-фантом.
COMMIT;

-- 5. Lost update: оба клиента прочитают одно старое значение.
UPDATE accounts SET balance_minor = 1000 WHERE id = '00000000-0000-0000-0000-000000000001';
BEGIN ISOLATION LEVEL READ COMMITTED;
SELECT balance_minor FROM accounts WHERE id = '00000000-0000-0000-0000-000000000001';
-- В session B выполните только BEGIN и SELECT секции 5, затем вернитесь сюда.
UPDATE accounts SET balance_minor = 1100 WHERE id = '00000000-0000-0000-0000-000000000001';
COMMIT;
-- Теперь в session B выполните UPDATE и COMMIT, затем проверьте: вместо 1300 получилось 1200.
SELECT balance_minor FROM accounts WHERE id = '00000000-0000-0000-0000-000000000001';

-- 6. Serializable: вместо молчаливой аномалии одна транзакция получит 40001.
UPDATE accounts SET balance_minor = 1000 WHERE id = '00000000-0000-0000-0000-000000000001';
BEGIN ISOLATION LEVEL SERIALIZABLE;
SELECT balance_minor FROM accounts WHERE id = '00000000-0000-0000-0000-000000000001';
-- В session B выполните только BEGIN и SELECT секции 6, затем вернитесь сюда.
UPDATE accounts SET balance_minor = 1100 WHERE id = '00000000-0000-0000-0000-000000000001';
COMMIT;
-- Теперь UPDATE/COMMIT в session B завершится serialization_failure (SQLSTATE 40001).
