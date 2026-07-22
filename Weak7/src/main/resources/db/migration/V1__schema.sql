CREATE TABLE accounts (
    id uuid PRIMARY KEY,
    balance_minor bigint NOT NULL CHECK (balance_minor >= 0)
);
CREATE TABLE transfers (
    id uuid PRIMARY KEY,
    idempotency_key text NOT NULL UNIQUE,
    from_account_id uuid NOT NULL REFERENCES accounts(id),
    to_account_id uuid NOT NULL REFERENCES accounts(id),
    amount_minor bigint NOT NULL CHECK (amount_minor > 0),
    status text NOT NULL DEFAULT 'COMPLETED'
);
CREATE TABLE ledger_entries (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    transfer_id uuid NOT NULL REFERENCES transfers(id),
    account_id uuid NOT NULL REFERENCES accounts(id),
    amount_minor bigint NOT NULL CHECK (amount_minor <> 0)
);

