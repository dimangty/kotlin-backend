# Неделя 8. Spring Data, JDBC/JPA, миграции и границы транзакций

**Результат недели:** перенести понимание SQL и транзакций из недель 3-7 в Spring, не спрятав базу за ORM.

Правило недели: ORM не отменяет ни одного вывода предыдущих недель. Тот же план выполнения, те же блокировки, те же границы транзакций - меняется только то, кто печатает SQL. Если сгенерированный запрос вас удивил, значит, ORM работает не за вас, а вместо вас.

## Кто чем владеет

| Слой | Владелец | В проекте |
|---|---|---|
| Схема | Flyway | [V1__payments.sql](src/main/resources/db/migration/V1__payments.sql) |
| Проверка схемы | Hibernate `validate` | `spring.jpa.hibernate.ddl-auto: validate` в [application.yaml](src/main/resources/application.yaml) |
| Изменение одной сущности | JPA + dirty checking | `PaymentService.complete` |
| Аналитический запрос | явный SQL | `PaymentService.dailyTotals` через `JdbcTemplate` |
| Граница транзакции | service method | `@Transactional` на публичном методе сервиса |

`ddl-auto: validate` - принципиальный выбор: Hibernate никогда не меняет схему, только сверяет её с ожиданиями. Схема принадлежит миграциям, и это единственный способ выкатывать её безопасно (см. `migration-lab.sql` ниже).

## Теория и где она в проекте

| Тема | Где смотреть |
|---|---|
| JDBC connection, pool, association с транзакцией | HikariCP приходит со стартером, размер пула - предмет задания 5 |
| Entity lifecycle и dirty checking | [PaymentService.kt](src/main/kotlin/study/week8/PaymentService.kt) - `status` меняется без единого `save()`, UPDATE появится на commit |
| `@Transactional` как proxy | там же: транзакция начинается на входе в публичный метод бина, а не на строчке кода |
| Self-invocation обходит proxy | задание 4 |
| JPA против JdbcTemplate | один и тот же класс использует оба подхода осознанно: lifecycle - для перехода состояния, SQL - для агрегата |
| `open-in-view: false` | [application.yaml](src/main/resources/application.yaml) - lazy loading не должен продолжаться во время рендеринга ответа |
| Flyway и безопасные миграции | [migration-lab.sql](migration-lab.sql) |

## Запуск

```bash
docker compose up -d          # PG_PORT=55432 docker compose up -d, если 5432 занят
./gradlew test                # Testcontainers: Flyway + dirty checking + JDBC projection
./gradlew bootRun
```

## Лаборатория миграций

Отдельная часть недели, отвечающая на вопрос плана «как выкатить обязательную колонку на большой таблице»:

```bash
docker compose exec -T postgres psql -U study -d data -v ON_ERROR_STOP=1 < migration-lab.sql
```

Скрипт создаёт таблицу на 500 000 строк (60 MB) и измеряет то, что обычно обсуждают на словах:

| Блок | Что показывает | Замеренный результат |
|---|---|---|
| 1 | `ADD COLUMN ... DEFAULT` не переписывает таблицу | значение лежит в `pg_attribute.attmissingval`, время - доли миллисекунды |
| 2 | прямой `SET NOT NULL` читает всю таблицу | ~40 мс под ACCESS EXCLUSIVE |
| 3 | `CHECK ... NOT VALID` -> `VALIDATE` -> `SET NOT NULL` | 0.4 мс + 17 мс под SHARE UPDATE EXCLUSIVE + 0.4 мс |
| 4 | какой `ALTER TYPE` переписывает файл | `text -> varchar(32)` переписывает, `varchar(32) -> varchar(64)` нет, `bigint -> numeric` переписывает |
| 5 | `CREATE INDEX` против `CONCURRENTLY` | 107 мс с блокировкой записи против 146 мс без неё |
| 6 | бэкофилл батчами и цена в мёртвых версиях строк | 10 батчей, затем `VACUUM` обнуляет `n_dead_tup` |
| 7 | `lock_timeout` как страховка деплоя | миграция сдаётся, а не строит за собой очередь |

Главный вывод блоков 2-3: на 500 000 строк разница выглядит как 40 мс против 0.4 мс, но масштабируется линейно - на таблице в тысячу раз больше первый вариант превращается в десятки секунд полной недоступности, потому что за ACCESS EXCLUSIVE встают в очередь даже `SELECT`. Процедура выкатки на основе этих замеров описана в [Weak13/docs/safe-deploy.md](../Weak13/docs/safe-deploy.md).

## Задания

1. **Controller.** Добавить HTTP-слой поверх сервиса (`POST /payments`, `POST /payments/{id}/complete`, `GET /accounts/{id}/daily-totals`) с error contract из недели 2. Сейчас это data-layer slice без транспорта.
2. **N+1.** Завести `accounts` и связь `account -> payments`, включить `spring.jpa.properties.hibernate.format_sql` и `logging.level.org.hibernate.SQL=DEBUG`, вывести список из 20 счетов с их платежами и посчитать запросы в логе. Затем исправить двумя способами - `JOIN FETCH` и projection - и сравнить: fetch join тянет полные сущности, projection только нужные колонки.
3. **Границы транзакции.** Добавить внешний вызов (`Thread.sleep(2000)` как заглушка) внутрь `@Transactional`-метода и посмотреть в `pg_stat_activity`, что connection всё это время занят в состоянии `idle in transaction`. Вынести вызов за границу транзакции. Это та же ошибка, которую неделя 11 решает системно.
4. **Self-invocation.** Вызвать `@Transactional`-метод из другого метода того же бина и показать, что транзакция не началась. Объяснить причину через proxy-based AOP.
5. **Пул соединений.** Поставить `spring.datasource.hikari.maximum-pool-size: 2`, запустить 10 параллельных запросов и увидеть таймаут получения connection. Связать это с thread-pool saturation недели 1: пул соединений - второе узкое место, и оно меньше пула потоков.
6. **План выполнения.** Снять `EXPLAIN (ANALYZE, BUFFERS)` для запроса `dailyTotals` на осмысленном объёме данных и решить, нужен ли индекс сверх существующего `payments(account_id, created_at DESC)`. Ответ оформить по форме из [docs/index-track.md](../docs/index-track.md).

## Что разобрать с ментором

- Границы транзакций и фактически сгенерированный SQL: где вы его видели своими глазами, а где предполагаете.
- Миграция обязательной колонки на большой таблице - по замерам из `migration-lab.sql`, а не по общим словам.
- Когда JPA уместна, когда `JdbcTemplate`, и почему смешивать их в одном проекте нормально.

## Критерий готовности

- Ни один критичный запрос не является неожиданностью: SQL и план проверены.
- Можешь объяснить, где заканчивается Spring и начинается PostgreSQL.
- Можешь показать N+1 в логе и убрать его двумя разными способами.

## Контрольные вопросы

- Почему `ddl-auto: validate`, а не `update`?
- В какой момент dirty checking выполняет UPDATE и что произойдёт, если транзакции нет?
- Почему `@Transactional` на private-методе или при self-invocation не работает?
- Почему `open-in-view: true` опасен для сервиса с пулом в 10 соединений?
- Чем `CHECK ... NOT VALID` + `VALIDATE` лучше прямого `SET NOT NULL`, если суммарное время почти то же самое?

## Материалы

- [Spring Data JPA Reference](https://docs.spring.io/spring-data/jpa/reference/index.html)
- [Spring Framework: Transaction Management](https://docs.spring.io/spring-framework/reference/data-access/transaction.html)
- [Flyway: Migrations](https://documentation.red-gate.com/fd/migrations-271585107.html)
- [PostgreSQL: ALTER TABLE](https://www.postgresql.org/docs/17/sql-altertable.html)
