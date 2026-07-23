-- Неделя 7, session B. Выполняйте только после подсказки в locks-session-a.sql.
--
--   docker compose exec postgres psql -U study -d locks

SELECT pg_backend_pid() AS session_b_pid;

-- =================== 1. Ожидание чужой блокировки ===================

BEGIN;
-- Этот UPDATE повиснет: строку A держит session A.
UPDATE accounts SET balance_minor = balance_minor + 100
WHERE id = 'aaaaaaaa-0000-0000-0000-000000000001';
-- Пройдёт только после COMMIT в session A. Если ждать дольше lock_timeout,
-- вместо результата придёт SQLSTATE 55P03 - это нормальное поведение,
-- а не ошибка лаборатории.
COMMIT;

-- =========== 2. Deadlock: обратный порядок захвата ===========

BEGIN;
-- B идёт в порядке B -> A, то есть навстречу session A.
UPDATE accounts SET balance_minor = balance_minor - 100
WHERE id = 'bbbbbbbb-0000-0000-0000-000000000002';

-- A: выполните второй UPDATE секции 2.

-- Теперь B просит A. Одна из двух транзакций получит
-- ERROR: deadlock detected (SQLSTATE 40P01) и будет откачена.
UPDATE accounts SET balance_minor = balance_minor + 100
WHERE id = 'aaaaaaaa-0000-0000-0000-000000000001';

ROLLBACK; -- если жертвой стала A, закройте свою транзакцию явно

-- =========== 3. Единый порядок: ожидание вместо deadlock ===========

BEGIN;
SELECT id, balance_minor FROM accounts
WHERE id IN ('aaaaaaaa-0000-0000-0000-000000000001', 'bbbbbbbb-0000-0000-0000-000000000002')
ORDER BY id
FOR UPDATE;
-- Тот же ORDER BY id, что и в session A. Порядок общий - цикла нет.
UPDATE accounts SET balance_minor = balance_minor - 100 WHERE id = 'bbbbbbbb-0000-0000-0000-000000000002';
UPDATE accounts SET balance_minor = balance_minor + 100 WHERE id = 'aaaaaaaa-0000-0000-0000-000000000001';
COMMIT;

-- =========== 4. Очередь заданий: SKIP LOCKED ===========

BEGIN;
SELECT id, payload FROM jobs
WHERE status = 'READY'
ORDER BY id
LIMIT 3
FOR UPDATE SKIP LOCKED;
-- Сравните id с теми, что получила session A: наборы не должны пересекаться.
COMMIT;

-- 5. То же самое без SKIP LOCKED: теперь это ожидание.
BEGIN;
SELECT id FROM jobs WHERE status = 'READY' ORDER BY id LIMIT 3 FOR UPDATE;
COMMIT;
