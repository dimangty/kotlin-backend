-- Неделя 4, вторая половина: сколько стоит индекс на записи.
--
-- lab.sql показывает выигрыш индекса на чтении. Этот файл показывает счёт,
-- который за него выставляют INSERT, WAL и диск.
--
--   docker compose exec -T postgres psql -U study -d indexes -v ON_ERROR_STOP=1 < write-overhead.sql
--
-- Три структурно одинаковые таблицы, разное число индексов, один и тот же
-- INSERT на 300 000 строк. Сравнение честное только при одинаковых данных,
-- поэтому источник строк общий (таблица source), а не random() в каждом INSERT.

\timing off

DROP TABLE IF EXISTS source, wo_none, wo_one, wo_many;

CREATE TABLE source AS
SELECT n AS id,
       1 + (random() * 99999)::bigint AS user_id,
       (ARRAY['NEW','DONE','FAILED'])[1 + (random() * 2)::int] AS status,
       gen_random_uuid() AS public_id,
       now() - random() * interval '365 days' AS created_at,
       repeat('x', 40) AS note   -- payload, который не входит ни в один индекс
FROM generate_series(1, 300000) AS n;
ANALYZE source;

CREATE TABLE wo_none (LIKE source);
CREATE TABLE wo_one  (LIKE source);
CREATE TABLE wo_many (LIKE source);

-- Один индекс: монотонный ключ, дешёвая вставка в правый край B-tree.
CREATE INDEX wo_one_id_idx ON wo_one(id);

-- Четыре индекса, включая случайный UUID: каждая вставка попадает в
-- произвольную leaf page, отсюда random I/O, page splits и лишний WAL.
CREATE INDEX wo_many_id_idx        ON wo_many(id);
CREATE INDEX wo_many_public_id_idx ON wo_many(public_id);
CREATE INDEX wo_many_created_idx   ON wo_many(created_at);
CREATE INDEX wo_many_user_status_idx ON wo_many(user_id, status);

-- WAL в выводе EXPLAIN - это то, что уедет на реплику и в архив.
-- Смотрите не только "Execution Time", но и "WAL: bytes".
\echo '=== 0 индексов ==='
EXPLAIN (ANALYZE, BUFFERS, WAL, COSTS OFF)
INSERT INTO wo_none SELECT * FROM source;

\echo '=== 1 индекс (монотонный bigint) ==='
EXPLAIN (ANALYZE, BUFFERS, WAL, COSTS OFF)
INSERT INTO wo_one SELECT * FROM source;

\echo '=== 4 индекса (включая случайный UUID) ==='
EXPLAIN (ANALYZE, BUFFERS, WAL, COSTS OFF)
INSERT INTO wo_many SELECT * FROM source;

-- Второе измерение цены: место на диске.
\echo '=== размеры ==='
SELECT relname,
       pg_size_pretty(pg_relation_size(oid))        AS heap,
       pg_size_pretty(pg_indexes_size(oid))         AS indexes,
       pg_size_pretty(pg_total_relation_size(oid))  AS total,
       round(100.0 * pg_indexes_size(oid) / NULLIF(pg_relation_size(oid), 0), 1) AS index_pct_of_heap
FROM pg_class
WHERE relname IN ('wo_none', 'wo_one', 'wo_many')
ORDER BY pg_total_relation_size(oid);

-- Третье измерение: UPDATE колонки `note`, не входящей ни в один индекс.
-- HOT update обновляет только heap и не трогает индексы, но лишь при двух
-- условиях: изменённая колонка не проиндексирована И на странице есть
-- свободное место. Таблицы залиты bulk INSERT при fillfactor 100, свободного
-- места почти нет - посмотрите, какая доля обновлений реально стала HOT.
\echo '=== UPDATE неиндексированной колонки ==='
EXPLAIN (ANALYZE, BUFFERS, WAL, COSTS OFF)
UPDATE wo_none SET note = 'updated' WHERE id <= 50000;
EXPLAIN (ANALYZE, BUFFERS, WAL, COSTS OFF)
UPDATE wo_many SET note = 'updated' WHERE id <= 50000;

-- Счётчики pg_stat_* накапливаются в backend и попадают в общий снимок не
-- мгновенно. Без принудительного flush следующий запрос покажет нули.
SELECT pg_stat_force_next_flush();

SELECT relname, n_tup_upd, n_tup_hot_upd,
       round(100.0 * n_tup_hot_upd / NULLIF(n_tup_upd, 0), 1) AS hot_pct
FROM pg_stat_user_tables
WHERE relname IN ('wo_none', 'wo_many');

-- Ожидаемый вывод для отчёта недели:
--   запрос -> выигрыш на чтении (lab.sql) -> цена в ms, WAL и байтах (здесь).
-- Индекс, у которого нет конкретного запроса из первой колонки, - это чистый
-- убыток: он оплачивается каждым INSERT и не возвращает ничего.
