# Неделя 6-1. Транзакции, ACID, MVCC и уровни изоляции (Spring-вариант)

**Результат недели:** три способа безопасного списания реализованы и сравнимы в одном сервисе: atomic conditional UPDATE, `SELECT ... FOR UPDATE` в Read Committed и read-compute-write в Serializable с ограниченным retry всей транзакции.

Сначала пройдите двухсессионную SQL-лабораторию [Weak6](../Weak6/) — те же сценарии сохранены здесь в `session-a.sql` / `session-b.sql`. Теория недели: [THEORY-SHORT.md](THEORY-SHORT.md) и [THEORY-DETAILED.md](THEORY-DETAILED.md).

## Теория и где она в коде

| Тема плана | Где в проекте |
|---|---|
| Безопасное списание №1: atomic conditional UPDATE | `atomicDebit` в [AccountService.kt](src/main/kotlin/study/week6copy/AccountService.kt) — проверка и запись в одном statement |
| Безопасное списание №2: row lock | `lockedDebit` там же — `SELECT ... FOR UPDATE` внутри `@Transactional(READ_COMMITTED)` |
| Безопасное списание №3: Serializable + retry | [SerializableDebitService.kt](src/main/kotlin/study/week6copy/SerializableDebitService.kt) — `TransactionTemplate` с `ISOLATION_SERIALIZABLE` |
| Классификация retryable ошибок | `isSerializationFailure` ищет SQLSTATE `40001` по цепочке cause; `InsufficientFundsException` не повторяется |
| Retry всей бизнес-транзакции, а не statement | цикл в `SerializableDebitService.debit`: новый snapshot, повторная проверка остатка |
| Границы транзакции в сервисном слое | `@Transactional` на методе сервиса, а не контроллера |
| Инвариант базы поверх любого способа | `CHECK (balance_minor >= 0)` в [V1__accounts.sql](src/main/resources/db/migration/V1__accounts.sql) |
| Аномалии вручную в двух сессиях | `session-a.sql` / `session-b.sql` на отдельной базе `postgres-lab` |

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

Проверка: `./gradlew test` запускает конкурентные сценарии на настоящем PostgreSQL 17.

## Задания

1. Пошагово выполнить `session-a.sql` и `session-b.sql`: non-repeatable read, lost update, стабильный Repeatable Read snapshot и serialization failure.
2. Убрать atomic predicate (`AND balance_minor >= ?`) и воспроизвести lost update конкурентными запросами к приложению.
3. Сравнить atomic UPDATE и row lock: round trips, время удержания lock и удобство сложной бизнес-проверки между чтением и записью.
4. Проследить, что retry для SQLSTATE `40001` повторяет чтение и проверку, а `INSUFFICIENT_FUNDS` не повторяется.
5. Отправить 50 параллельных списаний на каждый из трёх endpoint'ов и убедиться, что баланс никогда не уходит в минус и не теряется ни одно списание.

## Что разобрать с ментором

- Каждая аномалия по timeline двух транзакций — с собственными записями из SQL-лаборатории.
- Где должна начинаться транзакция в сервисном слое и почему `atomicDebit` работает вообще без `@Transactional`.
- Какие ошибки безопасно retry автоматически, сколько попыток допустимо и что логировать при каждом повторе.

## Критерий готовности

- Можешь предсказать поведение каждого из трёх endpoint'ов под конкурентной нагрузкой до запуска теста.
- Понимаешь, какие ошибки нужно retry всей транзакцией, а какие нельзя повторять автоматически.
- Можешь объяснить, чем отличаются три способа по блокировкам, round trips и применимости.

## Контрольные вопросы

- Может ли Read Committed потерять обновление? Почему `atomicDebit` при этом безопасен?
- Чем Repeatable Read отличается от Serializable в PostgreSQL?
- Почему retry должен повторять всю бизнес-транзакцию, а не последний statement?
- Почему backoff в `SerializableDebitService` выполняется после rollback, а не внутри транзакции?

## Материалы

- [PostgreSQL: Transaction Isolation](https://www.postgresql.org/docs/17/transaction-iso.html) — уровни изоляции и `40001`.
- [PostgreSQL: Explicit Locking](https://www.postgresql.org/docs/17/explicit-locking.html) — row locks и `FOR UPDATE`.
- [Spring Framework: Transaction Management](https://docs.spring.io/spring-framework/reference/data-access/transaction.html) — `@Transactional`, `TransactionTemplate`, isolation.
