# Неделя 3-1. PostgreSQL: схема, SQL и физическое хранение (Spring-вариант)

**Результат недели:** видна граница между HTTP, JDBC и ограничениями базы: какие инварианты держит Bean Validation, а какие — только PostgreSQL. Физическая версия строки (`ctid`/`xmin`) наблюдается прямо через API.

Сначала пройдите SQL-first лабораторию [Weak3](../Weak3/) — этот проект надстраивает над той же схемой приложение. Теория недели: [THEORY-SHORT.md](THEORY-SHORT.md) и [THEORY-DETAILED.md](THEORY-DETAILED.md).

## Теория и где она в коде

| Тема плана | Где в проекте |
|---|---|
| Схема users/accounts/payments/ledger_entries/idempotency_keys | [V1__fintech_schema.sql](src/main/resources/db/migration/V1__fintech_schema.sql) |
| PK, FK, UNIQUE, CHECK, NOT NULL и инварианты базы | `CHECK (balance_minor >= 0)`, `UNIQUE (owner_id, currency)`, expression-уникальность `lower(email)` в той же миграции |
| Ledger против mutable projection | CTE-запрос `accountSnapshot` в [FintechService.kt](src/main/kotlin/study/week3copy/FintechService.kt) сравнивает `balance_minor` и сумму проводок |
| CTID и xmin: физическая версия строки | `physicalTuple` в `FintechService.kt` и маршрут `/physical-tuple` |
| SQL без ORM: INSERT ... RETURNING, JOIN, CTE | все методы `FintechService.kt` используют `JdbcTemplate` и явный SQL |
| Реальный PostgreSQL в тестах | [FintechServiceIntegrationTest.kt](src/test/kotlin/study/week3copy/FintechServiceIntegrationTest.kt) на Testcontainers |

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

Исходный seed и запросы SQL-first лаборатории можно применить отдельно:

```bash
docker compose exec -T postgres psql -U study -d fintech < sql/002_seed.sql
docker compose exec -T postgres psql -U study -d fintech < sql/queries.sql
```

Проверка: `./gradlew test` поднимает настоящий PostgreSQL 17 через Testcontainers.

## Задания

1. Найти, какие инварианты дублируются Bean Validation и DB constraints, и объяснить, зачем нужны оба уровня.
2. Обновить баланс, сравнить `ctid`/`xmin` до и после через `/physical-tuple`, затем выполнить `VACUUM (VERBOSE, ANALYZE)` и объяснить вывод.
3. Дописать `sql/queries.sql` до 20 запросов: JOIN, GROUP BY, HAVING, CTE и window functions.
4. Сгенерировать 100 000 платежей и сравнить `pg_relation_size`, статистику и план одного запроса до/после `ANALYZE`.
5. Отправить два `POST /api/users` с email, различающимся только регистром, и показать, на каком уровне запрос отклоняется.

## Что разобрать с ментором

- Какие инварианты обязаны защищаться базой, а какие допустимо оставить приложению — на примерах из этой схемы.
- Почему `accountSnapshot` сравнивает projection с суммой ledger и что означает расхождение.
- Почему на `ctid`/`xmin` нельзя строить бизнес-логику, хотя API их отдаёт.

## Критерий готовности

- Для каждого constraint из `V1__fintech_schema.sql` можешь назвать инвариант, который без него ломается.
- Можешь показать, что `UPDATE` создал новую физическую версию строки, не меняя логический `id`.
- Валидация запроса и ограничение базы не дублируют друг друга вслепую: объясняешь роль каждого уровня.

## Контрольные вопросы

- Почему `SELECT` может увидеть старую версию строки?
- Чем logical row отличается от physical tuple?
- Почему уникальность email должна жить в базе, если сервис уже пишет `lower(email)`?
- Что произойдёт с `ctid` строки после `UPDATE` и после `VACUUM FULL`?

## Материалы

- [PostgreSQL: Database Physical Storage](https://www.postgresql.org/docs/17/storage.html) — страницы, tuples, TID.
- [PostgreSQL: Concurrency Control](https://www.postgresql.org/docs/17/mvcc.html) — MVCC и видимость версий.
- [Spring Framework: Data Access with JDBC](https://docs.spring.io/spring-framework/reference/data-access/jdbc.html) — `JdbcTemplate`.
- [Testcontainers for Java: Postgres Module](https://java.testcontainers.org/modules/databases/postgres/) — интеграционные тесты.
