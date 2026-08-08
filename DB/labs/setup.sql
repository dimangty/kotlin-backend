-- Выполните после запуска приложения: Flyway к этому моменту уже создал схему.
-- Фиксированные UUID упрощают команды в двух терминалах.
TRUNCATE ledger_entries, transfers, jobs, payments, accounts RESTART IDENTITY CASCADE;

INSERT INTO accounts(id, owner_name, balance_minor)
VALUES ('00000000-0000-0000-0000-000000000001', 'Анна', 1000),
       ('00000000-0000-0000-0000-000000000002', 'Борис', 1000);

INSERT INTO jobs(payload)
SELECT 'job-' || n FROM generate_series(1, 20) AS n;

INSERT INTO payments(user_id, reference, status, amount_minor, created_at, metadata)
VALUES (42, 'PAY-SEED-1', 'COMPLETED', 100, now() - interval '2 days', '{"channel":"WEB"}'),
       (42, 'PAY-SEED-2', 'PENDING', 200, now() - interval '1 day', '{"channel":"MOBILE"}');

SELECT id, owner_name, balance_minor FROM accounts ORDER BY id;
