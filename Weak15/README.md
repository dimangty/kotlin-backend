# Неделя 15. Ktor + PostgreSQL

Явный JDBC transaction wrapper и Hikari pool показывают границу framework/database. Flyway применяет схему при старте. Locks берутся в едином порядке, rollback выполняется для любой ошибки, `UNIQUE(idempotency_key)` и две ledger entries защищают повторный запрос и денежный инвариант. Блокирующий JDBC вынесен с Netty event loop на `Dispatchers.IO`.

```bash
docker compose up -d
./gradlew run
```

## Задания

Добавить ownership/JWT и bounded retry для deadlock/serialization failure. Затем сравнить `Weak7` и `Weak15` по startup, wiring, SQL transparency и testing — базовые DB invariants уже подтверждены одинаковыми Testcontainers-сценариями.
