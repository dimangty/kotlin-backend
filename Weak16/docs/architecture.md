# Architecture decision record

- Money хранится в minor units (`bigint`), без floating point.
- `accounts.balance_minor` — быстрая projection; immutable `ledger_entries` — история.
- Два account rows блокируются по UUID order, поэтому встречные переводы не создают wait-for cycle.
- Idempotency опирается на UNIQUE constraint и `INSERT ... ON CONFLICT`; повтор с тем же ключом, но другим payload отклоняется.
- HTTP/network calls внутри transaction отсутствуют.
- Index `ledger(account_id,id desc) INCLUDE(...)` соответствует cursor/history query; перед production нужны EXPLAIN и realistic data.

## Failure timeline

1. Client отправляет transfer с key K.
2. Service блокирует оба account.
3. В одной transaction создаются transfer, две ledger entries, balances и audit.
4. Ответ потерян из-за timeout.
5. Client повторяет K; service возвращает существующий transfer, не меняя balances.
