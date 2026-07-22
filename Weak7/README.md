# Неделя 7. Locks, deadlocks и fintech correctness

Spring JDBC-сервис перевода: оба счёта блокируются в едином порядке, `UNIQUE(idempotency_key)` защищает retry, ledger хранит две неизменяемые проводки.

```bash
docker compose up -d
./gradlew bootRun
```

Перед запросом добавьте два UUID-счёта через `psql`. Затем отправьте одинаковый POST дважды и убедитесь, что второй перевод не создан.

## Задания

1. Сначала намеренно убрать сортировку locks и воспроизвести deadlock A->B / B->A.
2. Найти blocker через `pg_stat_activity` и `pg_locks`.
3. Запустить 50 параллельных переводов и проверить неизменность общей суммы.
4. Добавить ограниченный retry только для deadlock/serialization failure.

