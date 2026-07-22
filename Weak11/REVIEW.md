# Review

Статус: тест проходит; повтор операции вызывает gateway один раз. Сетевой timeout находится вне локальной transaction, cancellation не поглощается.

Ограничение: repository/gateway учебные in-memory реализации. Следующий этап — детерминированные timeout tests, классификация retryable errors и PostgreSQL outbox/state machine.

