-- Неделя 7, подготовка лаборатории блокировок.
--
--   docker compose up -d
--   docker compose exec -T postgres psql -U study -d locks -v ON_ERROR_STOP=1 < locks-setup.sql
--
-- Скрипт самодостаточен: он не требует запущенного Spring-приложения и
-- совместим со схемой Flyway из V1__schema.sql.

CREATE TABLE IF NOT EXISTS accounts (
    id uuid PRIMARY KEY,
    balance_minor bigint NOT NULL CHECK (balance_minor >= 0)
);

-- Очередь заданий для эксперимента со SKIP LOCKED.
CREATE TABLE IF NOT EXISTS jobs (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    status text NOT NULL DEFAULT 'READY' CHECK (status IN ('READY', 'DONE')),
    payload text NOT NULL
);

-- Два счёта с говорящими UUID: A и B.
INSERT INTO accounts(id, balance_minor) VALUES
    ('aaaaaaaa-0000-0000-0000-000000000001', 100000),
    ('bbbbbbbb-0000-0000-0000-000000000002', 100000)
ON CONFLICT (id) DO UPDATE SET balance_minor = 100000;

TRUNCATE jobs;
INSERT INTO jobs(payload)
SELECT 'job-' || n FROM generate_series(1, 10) AS n;

-- Короткий lock_timeout на уровне базы: зависшая транзакция должна падать,
-- а не ждать вечно. В production это одна из первых защит от инцидента.
-- Здесь он задан для роли study, чтобы эксперименты не блокировали друг друга
-- дольше десяти секунд.
ALTER ROLE study SET lock_timeout = '10s';

SELECT id, balance_minor FROM accounts ORDER BY id;
SELECT count(*) AS ready_jobs FROM jobs WHERE status = 'READY';
