CREATE TABLE accounts(
  id uuid PRIMARY KEY, owner_id uuid NOT NULL, currency char(3) NOT NULL,
  balance_minor bigint NOT NULL CHECK(balance_minor >= 0), UNIQUE(owner_id,currency)
);
CREATE TABLE transfers(
  id uuid PRIMARY KEY, idempotency_key text NOT NULL UNIQUE,
  from_account_id uuid NOT NULL REFERENCES accounts(id), to_account_id uuid NOT NULL REFERENCES accounts(id),
  amount_minor bigint NOT NULL CHECK(amount_minor > 0), status text NOT NULL DEFAULT 'COMPLETED'
);
CREATE TABLE ledger_entries(
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, transfer_id uuid NOT NULL REFERENCES transfers(id),
  account_id uuid NOT NULL REFERENCES accounts(id), amount_minor bigint NOT NULL CHECK(amount_minor <> 0)
);
CREATE INDEX ledger_account_id_desc_idx ON ledger_entries(account_id,id DESC) INCLUDE(transfer_id,amount_minor);
CREATE TABLE audit_events(
  id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, actor text NOT NULL, event_type text NOT NULL,
  entity_id uuid NOT NULL, created_at timestamptz NOT NULL DEFAULT now()
);

