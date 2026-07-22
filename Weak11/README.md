# Неделя 11. Coroutines, external services и resilience

Координатор разделяет локальное состояние и сетевой вызов: connection/transaction не удерживается во время ожидания gateway. Idempotency key проходит сквозь обе системы.

```bash
./gradlew test
./gradlew run
```

Cancellation и параллельный повтор одного key покрыты тестами: отмена оставляет `RESERVED` для reconciliation, а один coordinator не допускает позднего `FAILED` поверх `COMPLETED`. Следующие задания: exponential backoff+jitter только для transient errors, timeout после успешного charge и PostgreSQL outbox/state machine.
