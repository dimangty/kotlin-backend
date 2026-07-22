# Review

Статус: четыре теста проходят. Последовательные и параллельные повторы вызывают gateway один раз, другой payload с тем же key отклоняется, cancellation не поглощается и не помечает неопределённый внешний результат как `FAILED`.

Ограничение: repository/gateway учебные in-memory реализации. Следующий этап — детерминированные timeout tests, классификация retryable errors и PostgreSQL outbox/state machine.
