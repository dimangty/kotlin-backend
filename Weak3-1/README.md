# Неделя 3-1. PostgreSQL: схема, SQL и физическое хранение

Spring Boot/Kotlin-проект поверх PostgreSQL. Он показывает границу между HTTP, JDBC и ограничениями базы, а также позволяет увидеть `ctid`/`xmin` физической версии строки. Исходная SQL-first лаборатория сохранена в `sql/`.

## Запуск

```bash
docker compose up -d
./gradlew bootRun
```

PostgreSQL слушает `localhost:5433`, приложение — `localhost:8080`. Основные маршруты:

- `POST /api/users`
- `POST /api/accounts`
- `POST /api/payments`
- `GET /api/accounts/{id}/snapshot`
- `GET /api/accounts/{id}/physical-tuple`

## Что изучить

1. Найти, какие инварианты дублируются Bean Validation и DB constraints, и объяснить зачем.
2. Обновить баланс, сравнить `ctid`/`xmin` до и после, выполнить `VACUUM (VERBOSE, ANALYZE)`.
3. Дописать `sql/queries.sql` до 20 запросов: JOIN, GROUP BY, HAVING, CTE и window functions.
4. Сгенерировать 100 000 платежей и сравнить `pg_relation_size`, статистику и план запроса до/после `ANALYZE`.

После запуска приложения исходный seed можно применить отдельно:

```bash
docker compose exec -T postgres psql -U study -d fintech < sql/002_seed.sql
docker compose exec -T postgres psql -U study -d fintech < sql/queries.sql
```

Проверка: `./gradlew test` поднимает настоящий PostgreSQL 17 через Testcontainers.
