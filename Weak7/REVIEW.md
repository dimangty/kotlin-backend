# Review

Статус: компиляция и runtime/Flyway успешны. Два одинаковых POST вернули один transfer ID; balances 900/1100, transfers=1, ledger entries=2, ledger net=0.

Сильные стороны: ordered row locks, UNIQUE idempotency key, transaction boundary. Нужны автоматизированные Testcontainers tests на 50 параллельных переводов и deadlock retry.

