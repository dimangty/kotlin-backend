-- Неделя 5, вторая половина: индексы, которые не являются обычным B-tree.
--
--   docker compose exec -T postgres psql -U study -d plans -v ON_ERROR_STOP=1 < special-indexes.sql
--
-- Три случая, где B-tree по колонке не работает или работает плохо:
--   expression - predicate применяет функцию к колонке;
--   GIN        - в одной колонке лежит много значений (jsonb, массив, текст);
--   BRIN       - таблица большая, append-only и физически упорядочена.

DROP TABLE IF EXISTS operations;

-- Данные вставляются строго по возрастанию created_at: это append-only лог,
-- какими обычно и бывают таблицы событий. Физический порядок совпадает
-- с логическим - именно то условие, при котором BRIN имеет смысл.
CREATE TABLE operations AS
SELECT n AS id,
       'User-' || (1 + (n % 5000)) || '@Example.TEST' AS email,
       jsonb_build_object(
           'channel', (ARRAY['web','ios','android'])[1 + (n % 3)],
           'tags', CASE n % 3
                       WHEN 0 THEN jsonb_build_array('retry')
                       WHEN 1 THEN jsonb_build_array('manual', 'review')
                       ELSE jsonb_build_array()
                   END,
           'merchant', 'm-' || (n % 977)
       ) AS metadata,
       timestamptz '2024-01-01 00:00:00+00' + (n * interval '30 seconds') AS created_at
FROM generate_series(1, 1000000) AS n;

ALTER TABLE operations ADD PRIMARY KEY (id);
VACUUM (ANALYZE) operations;

-- ========================= 1. Expression index =========================

-- Поиск нечувствителен к регистру, поэтому в predicate стоит lower().
-- Обычный индекс по email здесь бесполезен: индексируется email,
-- а сравнивается lower(email) - для planner это разные выражения.
\echo '=== expression: обычный B-tree по колонке ==='
CREATE INDEX operations_email_idx ON operations(email);
ANALYZE operations;
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
SELECT id FROM operations WHERE lower(email) = 'user-42@example.test';

\echo '=== expression: индекс по выражению ==='
CREATE INDEX operations_email_lower_idx ON operations(lower(email));
ANALYZE operations;
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
SELECT id FROM operations WHERE lower(email) = 'user-42@example.test';

-- Цена: expression index пересчитывает функцию на каждый INSERT и UPDATE,
-- а функция обязана быть IMMUTABLE. now() или lower() с учётом локали
-- в индекс попасть не могут.
SELECT pg_size_pretty(pg_relation_size('operations_email_idx'))       AS plain_idx,
       pg_size_pretty(pg_relation_size('operations_email_lower_idx')) AS expr_idx;

-- ============================== 2. GIN ==============================

-- В jsonb-колонке лежит документ. B-tree умеет сравнивать документ целиком,
-- но не отвечает на вопрос "какие строки содержат этот ключ".
\echo '=== GIN: containment без индекса ==='
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
SELECT count(*) FROM operations WHERE metadata @> '{"channel":"ios"}';

\echo '=== GIN: jsonb_path_ops на том же неселективном predicate ==='
-- jsonb_path_ops меньше и быстрее для @>, но не поддерживает ? и ?|.
-- Выбор оператора класса - это выбор набора поддерживаемых запросов.
CREATE INDEX operations_metadata_gin ON operations USING gin (metadata jsonb_path_ops);
ANALYZE operations;
-- Внимание: planner переключится на Bitmap Heap Scan, но быстрее не станет.
-- 'channel' принимает три значения, predicate забирает треть таблицы, и после
-- индекса всё равно приходится прочитать те же страницы плюс сам индекс.
-- Урок тот же, что с low-cardinality status на неделе 4: тип индекса не
-- спасает неселективный predicate.
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
SELECT count(*) FROM operations WHERE metadata @> '{"channel":"ios"}';

-- А вот здесь GIN действительно выигрывает: merchant селективен,
-- из миллиона строк подходит около тысячи.
\echo '=== GIN: селективный merchant ==='
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
SELECT id FROM operations WHERE metadata @> '{"merchant":"m-42"}' LIMIT 100;

-- ============================== 3. BRIN ==============================

-- BRIN хранит min/max по диапазону страниц, а не ссылку на каждую строку.
-- Поэтому он крошечный, но полезен только при физической корреляции.
\echo '=== BRIN на коррелированной колонке ==='
CREATE INDEX operations_created_brin ON operations USING brin (created_at) WITH (pages_per_range = 32);
ANALYZE operations;
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
SELECT count(*) FROM operations
WHERE created_at >= timestamptz '2024-06-01' AND created_at < timestamptz '2024-06-08';

\echo '=== тот же запрос через B-tree ==='
CREATE INDEX operations_created_btree ON operations (created_at);
ANALYZE operations;
SET enable_bitmapscan = off;
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
SELECT count(*) FROM operations
WHERE created_at >= timestamptz '2024-06-01' AND created_at < timestamptz '2024-06-08';
RESET enable_bitmapscan;

-- Главная цифра этого блока - разница в размере, а не в скорости.
\echo '=== размеры индексов ==='
SELECT indexrelname,
       pg_size_pretty(pg_relation_size(indexrelid)) AS size
FROM pg_stat_user_indexes
WHERE relname = 'operations'
ORDER BY pg_relation_size(indexrelid) DESC;

-- Корреляция - это то, на чём держится BRIN. Проверьте её явно:
-- значение близко к 1 или -1 означает, что физический порядок совпадает
-- с логическим. После массовых UPDATE корреляция падает, и BRIN слепнет.
SELECT attname, correlation
FROM pg_stats
WHERE tablename = 'operations' AND attname IN ('id', 'created_at', 'email');
