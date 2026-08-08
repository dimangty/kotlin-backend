-- Денежные суммы храним в минимальных единицах (копейках), а не в float/double.
CREATE TABLE accounts (
    id uuid PRIMARY KEY,
    owner_name text NOT NULL,
    balance_minor bigint NOT NULL CHECK (balance_minor >= 0),
    version bigint NOT NULL DEFAULT 0
);

-- Запись перевода и две проводки позволяют проверить атомарность и согласованность.
CREATE TABLE transfers (
    id uuid PRIMARY KEY,
    from_account_id uuid NOT NULL REFERENCES accounts(id),
    to_account_id uuid NOT NULL REFERENCES accounts(id),
    amount_minor bigint NOT NULL CHECK (amount_minor > 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT transfers_accounts_differ CHECK (from_account_id <> to_account_id)
);

CREATE TABLE ledger_entries (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    transfer_id uuid NOT NULL REFERENCES transfers(id),
    account_id uuid NOT NULL REFERENCES accounts(id),
    amount_minor bigint NOT NULL,
    UNIQUE (transfer_id, account_id)
);

-- Таблица нужна для демонстрации phantom read и разных типов индексов.
CREATE TABLE payments (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id bigint NOT NULL,
    reference text NOT NULL UNIQUE,
    status text NOT NULL CHECK (status IN ('PENDING', 'COMPLETED', 'FAILED')),
    amount_minor bigint NOT NULL CHECK (amount_minor > 0),
    created_at timestamptz NOT NULL,
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb
);

-- В B-tree сначала стоит равенство, затем диапазон/сортировка.
-- INCLUDE добавляет данные в листовые страницы и может разрешить Index Only Scan.
CREATE INDEX payments_user_created_cover_idx
    ON payments(user_id, created_at DESC)
    INCLUDE (id, reference, status, amount_minor);

-- Частичный индекс мал, потому что хранит только редкие незавершённые платежи.
CREATE INDEX payments_pending_idx
    ON payments(created_at)
    INCLUDE (id, user_id, reference, amount_minor)
    WHERE status = 'PENDING';

-- Expression index работает для запросов с тем же выражением lower(reference).
CREATE INDEX payments_reference_lower_idx ON payments(lower(reference));

-- GIN подходит для поиска элементов jsonb, BRIN — для больших физически упорядоченных таблиц.
CREATE INDEX payments_metadata_gin_idx ON payments USING gin(metadata);
CREATE INDEX payments_created_brin_idx ON payments USING brin(created_at);

-- Очередь иллюстрирует SELECT ... FOR UPDATE SKIP LOCKED.
CREATE TABLE jobs (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    payload text NOT NULL,
    status text NOT NULL DEFAULT 'NEW' CHECK (status IN ('NEW', 'PROCESSING', 'DONE'))
);
