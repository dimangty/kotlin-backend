INSERT INTO users(email)
SELECT 'user-' || n || '@example.test' FROM generate_series(1, 1000) AS n;

INSERT INTO accounts(owner_id, currency, balance_minor)
SELECT id, 'RUB', 100000 FROM users;

INSERT INTO payments(account_id, amount_minor, status, created_at)
SELECT a.id,
       100 + (random() * 100000)::bigint,
       (ARRAY['PENDING','COMPLETED','FAILED'])[1 + (random() * 2)::int],
       now() - random() * interval '365 days'
FROM accounts a CROSS JOIN generate_series(1, 100);

ANALYZE;

