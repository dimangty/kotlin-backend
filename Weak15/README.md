# Неделя 15. Ktor + PostgreSQL: интеграция и тесты

**Результат недели:** закрепить перенос базы, транзакций и тестов между Kotlin-фреймворками и убедиться, что инварианты не зависят от фреймворка.

Это тот же перевод между счетами, что и в `Weak7`, но без Spring: без `@Transactional`, без Spring Data, без автоконфигурации DataSource. Границы транзакций видны как обычный Kotlin-код - и именно поэтому неделя полезна.

## Что осталось тем же, что и в Weak7

| Инвариант | Weak7 (Spring) | Weak15 (Ktor) |
|---|---|---|
| Единый порядок захвата блокировок | `ORDER BY id FOR UPDATE` | `ORDER BY id FOR UPDATE` |
| Идемпотентность | `UNIQUE(idempotency_key)` + `ON CONFLICT DO NOTHING` | то же |
| Повторная проверка после захвата блокировок | есть | есть |
| Отказ на тот же key с другим payload | есть | есть |
| Две проводки ledger с нулевой суммой | есть | есть |
| Проверка на 50 параллельных переводах | есть | есть |

Совпадает всё, кроме способа открыть транзакцию. Это и есть ответ на вопрос недели 14 о переносимости знаний.

## Что изменилось: транзакция стала явной

| Тема | Где смотреть |
|---|---|
| Границы транзакции руками | `inTransaction` в [TransferService.kt](src/main/kotlin/study/week15/TransferService.kt) - `autoCommit = false`, `commit`, `rollback` в `catch` для любой ошибки |
| Пул соединений | `HikariConfig` в [Application.kt](src/main/kotlin/study/week15/Application.kt): размер пула и уровень изоляции заданы явно, а не свойством в yaml |
| Миграции при старте | `Flyway.configure().dataSource(...).migrate()` - видимая строка, а не автоконфигурация |
| Закрытие ресурсов | `monitor.subscribe(ApplicationStopped) { dataSource.close() }` |
| Блокирующий JDBC вне event loop | `withContext(Dispatchers.IO)` вокруг вызова сервиса: JDBC блокирует поток, а у Netty их немного |
| Ошибки -> HTTP-коды | `StatusPages`: `IllegalArgumentException` -> `400`, `IllegalStateException` -> `409` |
| Схема | [V1__schema.sql](src/main/resources/db/migration/V1__schema.sql) - canonical; [schema.sql](schema.sql) - ручная точка входа для psql |

Ошибка, которую эта неделя показывает наглядно: в Ktor нет никого, кто откатит транзакцию за вас. Забытый `rollback` в `catch` не проявится в happy-path тесте и проявится в первом же инциденте.

## Запуск

```bash
docker compose up -d          # PG_PORT=55432 docker compose up -d, если 5432 занят
./gradlew test                # Testcontainers: идемпотентность, ledger, конкурентные переводы
./gradlew run
```

```bash
# Счета создаются напрямую в базе: HTTP-контракта для них здесь нет
docker compose exec -T postgres psql -U study -d ktor -c \
  "INSERT INTO accounts VALUES ('00000000-0000-0000-0000-000000000001', 1000),
                               ('00000000-0000-0000-0000-000000000002', 1000)"

curl -i -X POST localhost:8080/transfers \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: k-1' \
  -d '{"from":"00000000-0000-0000-0000-000000000001","to":"00000000-0000-0000-0000-000000000002","amountMinor":100}'
# повтор с тем же ключом: тот же id, балансы не меняются
```

## Задания

1. **Ownership и JWT.** Перенести аутентификацию из задания 1 недели 14 и разрешить перевод только владельцу счёта-источника. Тест: чужой principal получает `403`, а деньги не двигаются.
2. **Bounded retry.** Ограниченный повтор всей транзакции для `40001` и `40P01` с backoff и потолком попыток. Проверить тестом, что `23505` не повторяется. Классификация - в [docs/concurrency-track.md](../docs/concurrency-track.md).
3. **Deadlock-тест.** Повторить сценарий недели 10: убрать `ORDER BY id`, получить `40P01` на встречных переводах, вернуть порядок - тест снова зелёный.
4. **Accounts endpoint.** Добавить `POST /accounts` и `GET /accounts/{id}`, чтобы неделя не требовала ручных вставок в psql.
5. **Ledger с cursor pagination.** `GET /accounts/{id}/ledger?cursor=` поверх существующего индекса `ledger_entries(account_id, id DESC) INCLUDE (...)`. Снять `EXPLAIN (ANALYZE, BUFFERS)` и подтвердить Index Only Scan.
6. **Сравнительная таблица Spring и Ktor.** Заполнить по фактическому опыту `Weak7` против `Weak15`: время старта, объём кода, где транзакция становится видимой, стоимость ошибки, тестируемость, что легко забыть. Это обязательный артефакт, который потребуется на неделе 16.

## Что разобрать с ментором

- Проверить, не потерялись ли при переносе constraints и границы транзакций - построчно, а не на глаз.
- Сформировать сравнительную таблицу для будущего выбора стека командой.
- Что произойдёт, если убрать `withContext(Dispatchers.IO)`, и как это проявится под нагрузкой.

## Критерий готовности

- Одинаковые инварианты проходят в обеих реализациях (Spring и Ktor).
- Можешь аргументировать выбор Spring или Ktor для конкретной команды, а не по вкусу.
- Можешь показать в коде каждое место, где начинается и заканчивается транзакция.

## Контрольные вопросы

- Почему блокирующий JDBC на Netty event loop опасен, если запросов немного?
- Что произойдёт при исключении, если убрать `rollback` из `inTransaction`?
- Почему `maximumPoolSize` важнее, чем кажется, и как он связан с числом корутин?
- Чем `ON CONFLICT DO NOTHING` лучше перехвата `23505` внутри транзакции?
- Почему повторная проверка идемпотентности выполняется дважды - до и после захвата блокировок?

## Материалы

- [Ktor: Connecting to a database](https://ktor.io/docs/server-integrate-database.html)
- [HikariCP: Configuration](https://github.com/brettwooldridge/HikariCP#gear-configuration-knobs-baby)
- [Flyway: Migrations](https://documentation.red-gate.com/fd/migrations-271585107.html)
- [PostgreSQL: Explicit Locking](https://www.postgresql.org/docs/17/explicit-locking.html)
