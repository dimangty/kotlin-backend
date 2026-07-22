# Неделя 5-1. Составные и специальные индексы, EXPLAIN ANALYZE

Spring Boot/Kotlin API для истории платежей. Миграция содержит составной covering index, индекс с обратным порядком колонок, partial/expression индексы, GIN и BRIN. API возвращает планы с actual rows и buffers. Исходная SQL-лаборатория на миллион строк сохранена в `lab.sql`.

## Запуск

```bash
docker compose up -d
./gradlew bootRun
```

PostgreSQL слушает `localhost:5435`. Полезные маршруты:

- `POST /api/payments/generate` с JSON `{"count":50000}`
- `GET /api/payments/history?userId=42&from=2025-01-01T00:00:00Z`
- `GET /api/payments/history/plan?userId=42&from=2025-01-01T00:00:00Z`
- `GET /api/payments/pending?before=2026-07-22T00:00:00Z`
- `GET /api/payments/indexes`

## Лаборатория

1. Сравнить `(user_id, created_at)` и `(created_at, user_id)` на одинаковых данных.
2. Зафиксировать план до/после, execution time, buffers, размер индекса и цену массового INSERT.
3. После UPDATE проверить visibility map косвенно по `Heap Fetches`, затем выполнить VACUUM.
4. Подобрать запросы для expression, GIN и BRIN; объяснить, почему GiST нужен для других типов поиска.

`lab.sql` рассчитан на отдельную пустую базу: его таблица `payments` намеренно не совпадает с Flyway lifecycle приложения.

Проверка: `./gradlew test` подтверждает Index Only Scan и наличие всех учебных индексов на PostgreSQL 17.
