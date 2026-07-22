ALTER TABLE transfers
    ADD CONSTRAINT transfers_accounts_differ CHECK (from_account_id <> to_account_id),
    ADD CONSTRAINT transfers_status_known CHECK (status IN ('COMPLETED'));

ALTER TABLE ledger_entries
    ADD CONSTRAINT ledger_one_entry_per_account UNIQUE (transfer_id, account_id);
