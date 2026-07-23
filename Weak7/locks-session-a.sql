-- Неделя 7, session A. Выполняйте по одному statement, синхронно с session B.
-- Третье окно держите открытым на locks-inspect.sql - именно там видно,
-- кто кого блокирует.
--
--   docker compose exec postgres psql -U study -d locks
--
-- Запомните свой PID, он понадобится для чтения pg_locks.
SELECT pg_backend_pid() AS session_a_pid;

-- =================== 1. Lock wait: кто кого держит ===================

BEGIN;
UPDATE accounts SET balance_minor = balance_minor - 100
WHERE id = 'aaaaaaaa-0000-0000-0000-000000000001';
-- Строка A заблокирована до конца этой транзакции.
-- B: выполните секцию 1 - её UPDATE повиснет.
-- Inspect: запустите locks-inspect.sql и найдите blocking_pid = session_a_pid.

-- Пока не завершим транзакцию, session B ждёт. Это не deadlock, а обычный
-- lock wait: цикла нет, ожидание разрешится само.
COMMIT;
-- B: её UPDATE проходит сразу после COMMIT. Зафиксируйте время ожидания.

-- =========== 2. Deadlock: разный порядок захвата ресурсов ===========

-- Сбрасываем состояние.
UPDATE accounts SET balance_minor = 100000;

BEGIN;
-- A идёт в порядке A -> B.
UPDATE accounts SET balance_minor = balance_minor - 100
WHERE id = 'aaaaaaaa-0000-0000-0000-000000000001';

-- B: выполните первый UPDATE секции 2 (он берёт B). Дождитесь, вернитесь сюда.

-- Теперь A просит B, который держит session B.
UPDATE accounts SET balance_minor = balance_minor + 100
WHERE id = 'bbbbbbbb-0000-0000-0000-000000000002';
-- B: выполните второй UPDATE секции 2 - он попросит A.
-- Возник wait-for cycle. Примерно через deadlock_timeout (по умолчанию 1s)
-- PostgreSQL обнаружит цикл и откатит одну из транзакций с SQLSTATE 40P01.
-- Запишите, какая именно транзакция стала жертвой: это решает база, а не вы.

ROLLBACK; -- если жертвой стала B, закройте свою транзакцию явно

-- ============ 3. Тот же перевод, но с единым порядком locks ============

UPDATE accounts SET balance_minor = 100000;

BEGIN;
-- Ключевая строка всей недели: оба клиента блокируют счета в порядке id,
-- а не в порядке "сначала мой, потом чужой". Цикл становится невозможен.
SELECT id, balance_minor FROM accounts
WHERE id IN ('aaaaaaaa-0000-0000-0000-000000000001', 'bbbbbbbb-0000-0000-0000-000000000002')
ORDER BY id
FOR UPDATE;
-- B: выполните секцию 3. Она будет ждать, но не упадёт с deadlock.
UPDATE accounts SET balance_minor = balance_minor - 100 WHERE id = 'aaaaaaaa-0000-0000-0000-000000000001';
UPDATE accounts SET balance_minor = balance_minor + 100 WHERE id = 'bbbbbbbb-0000-0000-0000-000000000002';
COMMIT;
-- Ожидание вместо deadlock - это и есть выигрыш. Сумма должна остаться 200000.
SELECT sum(balance_minor) AS total FROM accounts;

-- ============== 4. Очередь заданий: SKIP LOCKED ==============

BEGIN;
-- Без SKIP LOCKED второй worker встал бы в очередь за первым.
-- С ним он просто берёт следующие свободные строки.
SELECT id, payload FROM jobs
WHERE status = 'READY'
ORDER BY id
LIMIT 3
FOR UPDATE SKIP LOCKED;
-- B: выполните секцию 4 и сравните полученные id - пересечений быть не должно.
COMMIT;

-- 5. Контроль: FOR UPDATE без SKIP LOCKED на тех же строках.
BEGIN;
SELECT id FROM jobs WHERE status = 'READY' ORDER BY id LIMIT 3 FOR UPDATE;
-- B: секция 5 - теперь она ждёт, а не обходит заблокированные строки.
COMMIT;
