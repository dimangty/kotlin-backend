CREATE TABLE payments (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id bigint NOT NULL,
    status text NOT NULL,
    amount_minor bigint NOT NULL,
    created_at timestamptz NOT NULL
);

INSERT INTO payments(user_id, status, amount_minor, created_at)
SELECT 1 + (random() * 9999)::bigint,
       CASE WHEN random() < .02 THEN 'PENDING' ELSE 'COMPLETED' END,
       100 + (random() * 100000)::bigint,
       now() - random() * interval '730 days'
FROM generate_series(1, 1000000);
ANALYZE payments;

-- Зафиксируйте этот baseline до создания индекса.
EXPLAIN (ANALYZE, BUFFERS)
SELECT id, amount_minor, created_at FROM payments
WHERE user_id = 42 AND created_at >= now() - interval '90 days'
ORDER BY created_at DESC LIMIT 50;

-- id и amount_minor включены, чтобы history query мог стать Index Only Scan
-- после VACUUM, если visibility map разрешает не читать heap.
CREATE INDEX payments_user_created_idx ON payments(user_id, created_at DESC) INCLUDE (id, amount_minor);
CREATE INDEX payments_created_user_idx ON payments(created_at DESC, user_id);
CREATE INDEX payments_pending_idx ON payments(created_at) INCLUDE (id) WHERE status = 'PENDING';
VACUUM (ANALYZE) payments;

EXPLAIN (ANALYZE, BUFFERS)
SELECT id, amount_minor, created_at FROM payments
WHERE user_id = 42 AND created_at >= now() - interval '90 days'
ORDER BY created_at DESC LIMIT 50;

-- Predicate совпадает с partial index, поэтому он применим безопасно.
EXPLAIN (ANALYZE, BUFFERS)
SELECT id FROM payments WHERE status = 'PENDING' AND created_at < now() - interval '10 minutes';

-- ================== Порядок колонок составного индекса ==================
--
-- Оба индекса содержат ровно те же две колонки. Пока доступен
-- (user_id, created_at), planner выбирает его и разница не видна.
-- Уберём его и повторим тот же запрос на (created_at, user_id).

DROP INDEX payments_user_created_idx;

-- Ведущая колонка теперь created_at, а user_id - только фильтр внутри
-- диапазона дат. Сравните с предыдущим планом по строке Buffers:
-- индексу приходится просмотреть все платежи за 90 дней, а не только свои.
EXPLAIN (ANALYZE, BUFFERS)
SELECT id, amount_minor, created_at FROM payments
WHERE user_id = 42 AND created_at >= now() - interval '90 days'
ORDER BY created_at DESC LIMIT 50;

-- Возвращаем индекс на место.
CREATE INDEX payments_user_created_idx ON payments(user_id, created_at DESC) INCLUDE (id, amount_minor);
VACUUM (ANALYZE) payments;

-- ============== Когда planner намеренно игнорирует индекс ==============
--
-- Тот же самый индекс, но без LIMIT и на широком диапазоне: запрос забирает
-- слишком большую долю таблицы, и random I/O по heap дороже, чем один
-- последовательный проход. Индекс существует - и не используется.
EXPLAIN (ANALYZE, BUFFERS)
SELECT count(*), sum(amount_minor) FROM payments
WHERE created_at >= now() - interval '400 days';

-- Проверка, что дело именно в оценке стоимости, а не в непригодности индекса:
-- запретим Seq Scan и посмотрим, во что обошёлся бы выбор индекса.
SET enable_seqscan = off;
EXPLAIN (ANALYZE, BUFFERS)
SELECT count(*), sum(amount_minor) FROM payments
WHERE created_at >= now() - interval '400 days';
RESET enable_seqscan;
-- Вывод для отчёта: сравните два последних плана по Buffers, а не по времени.
-- Время может оказаться близким (данные уже в cache), но индексный вариант
-- читает заметно больше страниц - и на холодном cache это станет разницей.
-- enable_seqscan - инструмент диагностики, а не настройка для production.
