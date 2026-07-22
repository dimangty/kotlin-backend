# Неделя 6-1. Транзакции, ACID, MVCC и уровни изоляции

Spring Boot/Kotlin-сервис безопасного списания. Он сравнивает atomic conditional UPDATE, `SELECT ... FOR UPDATE` в Read Committed и read-compute-write в Serializable с ограниченным retry всей транзакции. Исходные двухсессионные сценарии сохранены в `session-a.sql` и `session-b.sql`.

## Запуск

```bash
docker compose up -d
./gradlew bootRun
```

PostgreSQL слушает `localhost:5436`. Маршруты:

- `POST /api/accounts` с JSON `{"initialBalanceMinor":1000}`
- `GET /api/accounts/{id}`
- `POST /api/accounts/{id}/debits/atomic`
- `POST /api/accounts/{id}/debits/locked`
- `POST /api/accounts/{id}/debits/serializable`

Для каждого списания используется JSON `{"amountMinor":100}`.

Compose также поднимает отдельную SQL-first лабораторию на `localhost:5446`. Она использует исходную bigint-схему из `setup.sql`, поэтому не конфликтует с UUID/Flyway-схемой Spring-приложения:

```bash
docker compose exec postgres-lab psql -U study -d isolation_lab
```

## Лаборатория

1. Пошагово выполнить `session-a.sql` и `session-b.sql`: non-repeatable read, lost update, стабильный Repeatable Read snapshot и serialization failure.
2. Убрать atomic predicate и воспроизвести lost update в приложении.
3. Сравнить atomic UPDATE и row lock: round trips, время удержания lock и удобство сложной бизнес-проверки.
4. Проследить, что retry для SQLSTATE `40001` повторяет чтение и проверку, а `INSUFFICIENT_FUNDS` не повторяется.

Проверка: `./gradlew test` запускает конкурентные сценарии на настоящем PostgreSQL 17.
