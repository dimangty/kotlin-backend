CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE accounts (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    -- CHECK остаётся последней линией защиты: ни один кодовый путь не может commit
    -- отрицательный остаток, даже если в приложении появится ошибка.
    balance_minor bigint NOT NULL CHECK (balance_minor >= 0)
);
