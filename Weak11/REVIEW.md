# Review

Миграция 24 июля 2026: framework-neutral coroutine-лаборатория намеренно оставлена без Spring/Ktor; Kotlin обновлён до 2.3.21, четыре теста проходят.

Статус: четыре теста проходят. Последовательные и параллельные повторы вызывают gateway один раз, другой payload с тем же key отклоняется, cancellation не поглощается и не помечает неопределённый внешний результат как `FAILED`.

Ограничение: repository/gateway учебные in-memory реализации. Следующий этап — детерминированные timeout tests, классификация retryable errors и PostgreSQL outbox/state machine.
