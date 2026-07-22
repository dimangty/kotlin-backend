CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- UUID удобен как публичный идентификатор; нормализованную уникальность email
-- задаёт expression index сразу после таблицы.
CREATE TABLE users (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    email text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

-- Инвариант case-insensitive email живёт в базе, а не только в lower() сервисного слоя.
CREATE UNIQUE INDEX users_email_normalized_uidx ON users(lower(email));

CREATE TABLE accounts (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id uuid NOT NULL REFERENCES users(id),
    currency char(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    balance_minor bigint NOT NULL DEFAULT 0 CHECK (balance_minor >= 0),
    version bigint NOT NULL DEFAULT 0,
    UNIQUE (owner_id, currency)
);

CREATE TABLE payments (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id uuid NOT NULL REFERENCES accounts(id),
    amount_minor bigint NOT NULL CHECK (amount_minor > 0),
    status text NOT NULL CHECK (status IN ('PENDING', 'COMPLETED', 'FAILED')),
    created_at timestamptz NOT NULL DEFAULT now()
);

-- Ledger entries считаются неизменяемыми событиями. CHECK запрещает бессмысленную
-- нулевую проводку, а внешние ключи не дают ссылаться на отсутствующие сущности.
CREATE TABLE ledger_entries (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_id uuid NOT NULL REFERENCES accounts(id),
    payment_id uuid REFERENCES payments(id),
    amount_minor bigint NOT NULL CHECK (amount_minor <> 0),
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE idempotency_keys (
    key text PRIMARY KEY,
    request_hash text NOT NULL,
    payment_id uuid REFERENCES payments(id),
    created_at timestamptz NOT NULL DEFAULT now()
);
