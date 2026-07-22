BEGIN;
UPDATE accounts SET balance = balance - 100 WHERE id = 1;
COMMIT;

-- Для serialization failure запустите этот блок параллельно с таким же в A.
BEGIN ISOLATION LEVEL SERIALIZABLE;
SELECT sum(balance) FROM accounts;
UPDATE accounts SET balance = balance + 1 WHERE id = 2;
COMMIT;

