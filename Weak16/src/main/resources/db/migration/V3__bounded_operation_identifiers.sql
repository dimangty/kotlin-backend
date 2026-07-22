ALTER TABLE transfers
    ALTER COLUMN idempotency_key TYPE varchar(128);

ALTER TABLE audit_events
    ALTER COLUMN actor TYPE varchar(128);
