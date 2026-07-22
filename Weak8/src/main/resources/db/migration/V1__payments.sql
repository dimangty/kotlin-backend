CREATE TABLE payments (
    id uuid PRIMARY KEY,
    account_id uuid NOT NULL,
    amount_minor bigint NOT NULL CHECK (amount_minor > 0),
    status text NOT NULL CHECK (status IN ('PENDING','COMPLETED','FAILED')),
    created_at timestamptz NOT NULL
);
CREATE INDEX payments_account_created_idx ON payments(account_id, created_at DESC);

