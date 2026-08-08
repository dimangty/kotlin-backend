-- Для устойчивого сравнения нужно достаточно строк и актуальная статистика.
INSERT INTO payments(user_id, reference, status, amount_minor, created_at, metadata)
SELECT 1 + (random() * 999)::bigint,
       'PAY-LAB-' || gen_random_uuid(),
       CASE WHEN random() < .02 THEN 'PENDING' ELSE 'COMPLETED' END,
       100 + (random() * 100000)::bigint,
       now() - random() * interval '730 days',
       jsonb_build_object('channel', CASE WHEN n % 2 = 0 THEN 'MOBILE' ELSE 'WEB' END)
FROM generate_series(1, 100000) AS n;
ANALYZE payments;

-- План без составного индекса. ROLLBACK вернёт индекс без дорогого повторного CREATE.
BEGIN;
DROP INDEX payments_user_created_cover_idx;
EXPLAIN (ANALYZE, BUFFERS)
SELECT id, reference, status, amount_minor, created_at
FROM payments
WHERE user_id = 42 AND created_at >= now() - interval '365 days'
ORDER BY created_at DESC
LIMIT 50;
ROLLBACK;

-- Тот же запрос с составным covering index.
EXPLAIN (ANALYZE, BUFFERS)
SELECT id, reference, status, amount_minor, created_at
FROM payments
WHERE user_id = 42 AND created_at >= now() - interval '365 days'
ORDER BY created_at DESC
LIMIT 50;

-- Predicate запроса совпадает с predicate partial index.
EXPLAIN (ANALYZE, BUFFERS)
SELECT id, user_id, reference, amount_minor, created_at
FROM payments
WHERE status = 'PENDING' AND created_at < now()
ORDER BY created_at
LIMIT 50;

-- Выражение запроса совпадает с expression index.
EXPLAIN (ANALYZE, BUFFERS)
SELECT * FROM payments WHERE lower(reference) = lower('PAY-SEED-1');

-- Оператор @> поддерживается GIN-индексом jsonb.
EXPLAIN (ANALYZE, BUFFERS)
SELECT count(*) FROM payments WHERE metadata @> '{"channel":"MOBILE"}';
