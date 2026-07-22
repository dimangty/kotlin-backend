ALTER TABLE transfers
    ALTER COLUMN idempotency_key TYPE varchar(128);
