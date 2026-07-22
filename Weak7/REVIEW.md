# Review

Статус: Testcontainers integration test подтверждает конкурентный retry: два одновременных запроса возвращают один transfer ID; balances 900/1100, transfers=1, ledger entries=2. Повтор ключа с другим payload отклоняется.

Сильные стороны: ordered row locks, `INSERT ... ON CONFLICT`, повторная проверка после lock и DB constraints. Следующий шаг — 50 параллельных разнонаправленных переводов и bounded deadlock/serialization retry.
