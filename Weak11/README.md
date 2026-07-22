# Неделя 11. Coroutines, external services и resilience

Координатор разделяет локальное состояние и сетевой вызов: connection/transaction не удерживается во время ожидания gateway. Idempotency key проходит сквозь обе системы.

```bash
./gradlew test
./gradlew run
```

Задания: добавить exponential backoff+jitter только для transient errors, проверить cancellation, смоделировать timeout после успешного charge и заменить in-memory repository на outbox/state machine в PostgreSQL.

