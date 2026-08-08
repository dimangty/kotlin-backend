# DB — учебная лаборатория Spring Boot 4.1 и PostgreSQL

Проект собирает в одном месте пять связанных тем: **ACID, аномалии конкурентного доступа, уровни изоляции, блокировки и индексы**. Примеры используют Spring Boot 4.1.0, Kotlin, Spring JDBC, Flyway и PostgreSQL 17. В коде намеренно нет ORM: видны реальные SQL-запросы, границы транзакций и блокировки.

## Быстрый запуск

Нужны JDK 17+ и Docker:

```bash
docker compose up -d
./gradlew bootRun
```

PostgreSQL доступен на `localhost:5450`, приложение — на `localhost:8080`. Проверка всех автоматизированных сценариев:

```bash
./gradlew test
```

## Карта примеров

| Тема | Код или лаборатория | Главная идея |
|---|---|---|
| Atomicity | `AccountService.transfer` | ошибка после debit откатывает всю транзакцию |
| Consistency | Flyway-миграция | `CHECK`, `FK`, `UNIQUE` защищают инварианты независимо от приложения |
| Isolation | `IsolationService.observe` | snapshot зависит от уровня изоляции |
| Durability | обычный commit PostgreSQL | после успешного ответа данные переживают перезапуск приложения |
| Lost update | `unsafeReadModifyWrite` | read-compute-write под Read Committed теряет параллельное изменение |
| Serializable | `serializableChange` | SQLSTATE `40001` приводит к retry всей бизнес-транзакции |
| Row locks | `LockService` | `FOR UPDATE`, единый порядок блокировок, `SKIP LOCKED` |
| Индексы | `IndexService` и миграция | составной, covering, partial, expression, GIN и BRIN |

## 1. ACID

Создайте два счёта:

```bash
curl -s -X POST localhost:8080/api/db/accounts \
  -H 'Content-Type: application/json' \
  -d '{"ownerName":"Анна","initialBalanceMinor":100000}'

curl -s -X POST localhost:8080/api/db/accounts \
  -H 'Content-Type: application/json' \
  -d '{"ownerName":"Борис","initialBalanceMinor":50000}'
```

Подставьте полученные UUID. Успешный перевод:

```bash
curl -s -X POST localhost:8080/api/db/acid/transfers \
  -H 'Content-Type: application/json' \
  -d '{"fromAccountId":"FROM_UUID","toAccountId":"TO_UUID","amountMinor":1000}'
```

Теперь добавьте `"failAfterDebit":true`. Сервис выбросит учебную ошибку после первого `UPDATE`, но `@Transactional` откатит списание. Проверьте оба баланса через `GET /api/db/accounts/{id}`.

Что здесь соответствует ACID:

- **Atomicity** — debit, credit, перевод и две проводки фиксируются вместе либо вместе откатываются.
- **Consistency** — ограничения схемы не допускают отрицательный баланс, перевод на тот же счёт и битые ссылки.
- **Isolation** — незавершённые изменения не видны другим транзакциям; детали зависят от уровня.
- **Durability** — после commit данные хранятся в PostgreSQL, а не в памяти приложения.

## 2. Аномалии и уровни изоляции

Откройте два терминала `psql`:

```bash
docker compose exec postgres psql -U study -d database_labs
```

Сначала выполните [labs/setup.sql](labs/setup.sql), затем синхронно проходите [labs/anomalies-session-a.sql](labs/anomalies-session-a.sql) и [labs/anomalies-session-b.sql](labs/anomalies-session-b.sql). Не запускайте файл целиком: секции специально останавливаются в точках, где надо переключиться между терминалами.

Ожидаемое поведение PostgreSQL:

| Аномалия | Read Committed | Repeatable Read | Serializable |
|---|---:|---:|---:|
| Dirty read | нет | нет | нет |
| Non-repeatable read | возможен | нет | нет |
| Phantom read | возможен | нет из-за snapshot | нет |
| Lost update для read-compute-write | возможен | одна транзакция обычно получит `40001` | одна транзакция получит `40001` |
| Write skew | возможен | возможен | одна транзакция получит `40001` |

PostgreSQL не имеет отдельного поведения `READ UNCOMMITTED`: запрос этого уровня фактически работает как `READ COMMITTED` и не показывает грязные данные.

Через HTTP можно увидеть non-repeatable read. Запустите чтение с пятисекундной паузой:

```bash
curl -s 'localhost:8080/api/db/isolation/ACCOUNT_UUID?level=READ_COMMITTED&pauseMillis=5000'
```

Во время паузы измените счёт во втором терминале через `atomic-debit`. Повторите с `level=REPEATABLE_READ`: два чтения увидят один snapshot.

Маршрут `/api/db/anomalies/lost-update` намеренно содержит ошибочный read-compute-write. Маршрут `/api/db/isolation/serializable-change` выполняет тот же смысл в Serializable и повторяет всю транзакцию после `40001`.

## 3. Блокировки

Ручной сценарий находится в [labs/locks-session-a.sql](labs/locks-session-a.sql), [labs/locks-session-b.sql](labs/locks-session-b.sql) и [labs/locks-inspect.sql](labs/locks-inspect.sql). Нужны три терминала: владелец блокировки, ожидающая транзакция и наблюдатель.

REST-примеры:

- `POST /api/db/locks/hold` — берёт `FOR UPDATE` и удерживает lock до 15 секунд;
- `POST /api/db/locks/transfers` — блокирует два счёта всегда в порядке UUID, поэтому встречные переводы не образуют цикл;
- `POST /api/db/jobs/claim?limit=10` — забирает задания через `FOR UPDATE SKIP LOCKED`.

Единый порядок существенно снижает вероятность deadlock, но production-код всё равно должен уметь обрабатывать SQLSTATE `40P01`. Долгую внешнюю сеть внутри транзакции вызывать нельзя: всё это время connection и locks остаются занятыми.

## 4. Индексы

Сгенерируйте достаточно данных, иначе последовательное чтение маленькой таблицы будет дешевле индекса:

```bash
curl -s -X POST localhost:8080/api/db/indexes/payments/generate \
  -H 'Content-Type: application/json' \
  -d '{"count":100000}'

curl -s 'localhost:8080/api/db/indexes'
curl -s 'localhost:8080/api/db/indexes/payments/explain?userId=42&from=2025-01-01T00:00:00Z'
```

В [labs/indexes.sql](labs/indexes.sql) есть сравнение плана без индекса и с индексом. Смотрите не только на имя scan, но и на `actual rows`, расхождение estimate/actual, `Buffers`, время выполнения и стоимость записи.

Назначение индексов миграции:

- `(user_id, created_at DESC) INCLUDE (...)` обслуживает equality + range/sort и может дать Index Only Scan;
- partial index хранит только `PENDING`, поэтому дешевле полного;
- `lower(reference)` требует такого же выражения в `WHERE`;
- GIN индексирует содержимое `jsonb`;
- BRIN хранит краткие диапазонные сведения и полезен на очень больших коррелированных с физическим порядком таблицах.

Индекс ускоряет чтение ценой места и дополнительной работы на `INSERT`, `UPDATE`, `DELETE`. Создавать индекс «на всякий случай» нельзя: сначала нужен реальный запрос и его план.

## Задания для самостоятельной работы

1. Вызвать перевод с `failAfterDebit=true` и доказать запросами, что не сохранилась ни одна часть операции.
2. Параллельно выполнить два lost-update запроса с одинаковой паузой и сравнить ожидаемую сумму с фактической.
3. Повторить наблюдение счёта на Read Committed и Repeatable Read.
4. Убрать сортировку UUID в `LockService`, выполнить встречные переводы и получить `40P01`, затем вернуть сортировку.
5. Запустить двух worker-ов `claimJobs` и убедиться, что их наборы `id` не пересекаются.
6. Сравнить план истории до и после удаления составного индекса внутри транзакции.
7. Поменять порядок составного индекса на `(created_at, user_id)` и объяснить изменение buffers.
8. Проверить, почему partial index не подходит запросу с `status = 'COMPLETED'`.

## Структура

```text
DB/
├── labs/                         # двухсессионные SQL-упражнения
├── src/main/kotlin/study/db/     # Spring JDBC-сервисы и API
├── src/main/resources/db/        # Flyway-схема и индексы
└── src/test/kotlin/study/db/      # интеграционные проверки на PostgreSQL
```

Полезные первичные источники: [Spring Boot 4.1 Reference](https://docs.spring.io/spring-boot/reference/), [PostgreSQL Transaction Isolation](https://www.postgresql.org/docs/17/transaction-iso.html), [Explicit Locking](https://www.postgresql.org/docs/17/explicit-locking.html), [Indexes](https://www.postgresql.org/docs/17/indexes.html).
