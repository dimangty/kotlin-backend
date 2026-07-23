# Неделя 7. Locks, deadlocks и fintech correctness

**Результат недели:** уметь управлять конкурентным изменением данных и диагностировать зависшую операцию, а не только называть определения.

Spring JDBC-сервис перевода: оба счёта блокируются в едином порядке, `UNIQUE(idempotency_key)` защищает retry, ledger хранит две неизменяемые проводки.

Порядок прохождения тот же, что и на всём треке: сначала воспроизвести проблему двумя SQL-сессиями, потом смотреть на код приложения.

## Часть 1. SQL-лаборатория блокировок

```bash
docker compose up -d
docker compose exec -T postgres psql -U study -d locks -v ON_ERROR_STOP=1 < locks-setup.sql
```

Дальше нужны **три** окна `psql`:

```bash
docker compose exec postgres psql -U study -d locks   # session A
docker compose exec postgres psql -U study -d locks   # session B
docker compose exec postgres psql -U study -d locks   # inspect
```

Выполняйте `locks-session-a.sql` и `locks-session-b.sql` по секциям, синхронно, а третье окно используйте для `locks-inspect.sql` в момент зависания.

| Секция | Что воспроизводится | Что зафиксировать |
|---|---|---|
| 1 | обычный lock wait | `blocked_by`, время ожидания, что цикла нет |
| 2 | deadlock A->B / B->A | `SQLSTATE 40P01` и то, какую транзакцию база выбрала жертвой |
| 3 | тот же перевод с `ORDER BY id FOR UPDATE` | ожидание вместо deadlock, `sum(balance_minor)` не изменился |
| 4-5 | очередь заданий с `SKIP LOCKED` и без него | наборы `id` двух worker'ов не пересекаются |

`locks-setup.sql` задаёт роли `study` `lock_timeout = '10s'`: зависшая сессия должна падать с `55P03`, а не ждать бесконечно. Это первая строчка защиты, которую стоит переносить в production.

Что смотреть в `locks-inspect.sql`:

- `pg_blocking_pids()` - готовый ответ на вопрос "кто кого держит";
- `pg_locks` с `granted = false` - собственно очередь;
- `state = 'idle in transaction'` с большим `xact_age` - самая частая причина инцидента;
- `pg_stat_database.deadlocks` - метрика, которую стоит вывести на дашборд недели 12.

Ожидаемый вид deadlock в логе:

```
ERROR:  deadlock detected
DETAIL:  Process 95 waits for ShareLock on transaction 746; blocked by process 94.
        Process 94 waits for ShareLock on transaction 747; blocked by process 95.
```

## Часть 2. Приложение

```bash
./gradlew test     # включает тест 50 параллельных переводов
./gradlew bootRun
```

Перед запросом добавьте два UUID-счёта через `psql` (или переиспользуйте счета из `locks-setup.sql`). Затем отправьте одинаковый POST дважды и убедитесь, что второй перевод не создан.

В `TransferService.kt` сравните с лабораторией три места: сортировку id перед `FOR UPDATE`, повторную проверку idempotency key уже под locks и `ON CONFLICT DO NOTHING` вместо перехвата unique violation внутри транзакции.

## Задания

1. Убрать сортировку в `orderedIds` и получить deadlock уже не в psql, а в конкурентном тесте.
2. Добавить ограниченный retry только для `40P01` и `40001`, с backoff и потолком попыток; показать тестом, что неретраибельная ошибка не повторяется.
3. Заменить `FOR UPDATE` на `FOR NO KEY UPDATE` и объяснить, что изменилось для FK-проверок.
4. Реализовать обработчик очереди на `SKIP LOCKED` поверх таблицы `jobs` и показать, что два worker'а не берут одно задание.
5. Проверить, что `transfers_accounts_differ` и `ledger_one_entry_per_account` действительно ловят ошибку: временно удалить constraint, увидеть падение инварианта, вернуть обратно.

## Что разобрать с ментором

- Разбор `pg_locks`/`pg_stat_activity` на вашем собственном timeline, а не на примере из README.
- Где retry безопасен, а где повтор создаст второй платёж.
- Как логировать deadlock и serialization failure, чтобы инцидент читался по логам.

## Критерий готовности

- Параллельный тест не нарушает общий баланс и не создаёт двойную операцию.
- Можешь воспроизвести deadlock по своей записи timeline, а не по подсказкам из файла.
- Можешь за минуту назвать блокирующую сессию на живой базе.

## Контрольные вопросы

- Как возникает deadlock при переводе A -> B и B -> A?
- Когда optimistic lock лучше `SELECT FOR UPDATE`?
- Почему idempotency key должен подкрепляться UNIQUE constraint, а не проверкой в коде?
- Чем `lock_timeout` отличается от `statement_timeout` и что выбрать для платёжного endpoint?

## Материалы

- [PostgreSQL: Explicit Locking](https://www.postgresql.org/docs/17/explicit-locking.html)
- [PostgreSQL: The Locking Clause (`FOR UPDATE`, `SKIP LOCKED`)](https://www.postgresql.org/docs/17/sql-select.html#SQL-FOR-UPDATE-SHARE)
- [PostgreSQL: Monitoring Statistics](https://www.postgresql.org/docs/17/monitoring-stats.html)
