# Review

Статус: Testcontainers integration tests подтверждают конкурентный retry: два одновременных запроса возвращают один transfer ID; balances 900/1100, transfers=1, ledger entries=2. Повтор ключа с другим payload отклоняется. Отдельный прогон 50 встречных переводов сохраняет total balance и zero-sum ledger.

Сильные стороны: ordered row locks, `INSERT ... ON CONFLICT`, повторная проверка после lock, bounded idempotency key и DB constraints. Следующий шаг — bounded deadlock/serialization retry и ручной разбор blocker через `pg_locks`.
