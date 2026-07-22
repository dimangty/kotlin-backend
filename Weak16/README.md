# Неделя 16. Финальный fintech slice

Вертикальный сценарий: account -> idempotent transfer -> ordered locks -> two-sided ledger -> audit -> history/metrics.

```bash
docker compose up -d
./gradlew bootRun
```

Перед production-style review дополните проект:

1. Authentication/ownership из `Weak9` и единый error contract.
2. Testcontainers: duplicate retry, 50 parallel transfers, invariant total, rollback after injected failure.
3. EXPLAIN before/after на миллионе ledger entries.
4. Cursor pagination вместо фиксированного LIMIT.
5. Retry serialization/deadlock с bounded backoff и operation ID.

Архитектурные решения и timeout timeline находятся в `docs/architecture.md`.

