CREATE TABLE payments (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id bigint NOT NULL,
    reference text NOT NULL UNIQUE,
    status text NOT NULL CHECK (status IN ('PENDING', 'COMPLETED', 'FAILED')),
    amount_minor bigint NOT NULL CHECK (amount_minor > 0),
    created_at timestamptz NOT NULL,
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb
);

-- Equality по user_id идёт первым, range/sort по created_at — вторым.
-- INCLUDE хранит projection в leaf pages, не меняя порядок ключей.
CREATE INDEX payments_user_created_cover_idx
    ON payments(user_id, created_at DESC)
    INCLUDE (id, reference, status, amount_minor);

-- Контрпример для лаборатории: created_at первым хуже поддерживает lookup одного user.
CREATE INDEX payments_created_user_idx ON payments(created_at DESC, user_id);

-- Маленький partial index хранит только редкие незавершённые операции.
CREATE INDEX payments_pending_idx
    ON payments(created_at)
    INCLUDE (id, user_id, reference, amount_minor)
    WHERE status = 'PENDING';

-- Expression index полезен только когда запрос использует то же выражение lower(reference).
CREATE INDEX payments_reference_lower_idx ON payments(lower(reference));

-- GIN индексирует элементы jsonb; BRIN хранит summaries диапазонов больших append-only таблиц.
CREATE INDEX payments_metadata_gin_idx ON payments USING gin(metadata);
CREATE INDEX payments_created_brin_idx ON payments USING brin(created_at);
