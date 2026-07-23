# Неделя 3. PostgreSQL: схема, SQL и физическое хранение

**Результат недели:** таблица перестаёт выглядеть списком объектов и становится набором страниц и версий строк. SQL-first лаборатория финтех-схемы: ограничения защищают инварианты без участия Kotlin-кода.

## Запуск

```bash
docker compose up -d
docker compose exec postgres psql -U study -d fintech
```

`sql/` монтируется в `docker-entrypoint-initdb.d`, поэтому схема, seed и `queries.sql` выполняются при первом старте автоматически. Полный сброс лаборатории - `docker compose down -v`.

## Порядок работы

1. **Посмотреть на расхождение.** Сразу после старта `ledger_entries` пуст, а `accounts.balance_minor` уже заполнен. Запросы 1-3 из `sql/queries.sql` показывают ровно эту ситуацию: projection есть, истории нет.
2. **Закрыть расхождение.** `ledger.sql` создаёт immutable проводки и пересчитывает projection **из них**:

   ```bash
   docker compose exec -T postgres psql -U study -d fintech -v ON_ERROR_STOP=1 < ledger.sql
   ```

   В конце скрипт печатает `drifted_accounts` и `unposted_payments` - обе цифры должны быть нулевыми. Там же видно, как `UPDATE` на 1000 строк оставляет 1000 dead tuples и как их убирает `VACUUM`.
3. **Перечитать запросы.** Прогнать `queries.sql` ещё раз и сравнить блок A до и после:

   ```bash
   docker compose exec -T postgres psql -U study -d fintech \
     -v ON_ERROR_STOP=1 -f /docker-entrypoint-initdb.d/queries.sql
   ```

## Что в `queries.sql`

| Блок | Запросы | О чём |
|---|---|---|
| A | 1-3 | согласованность projection и ledger |
| B | 4-9 | JOIN, LEFT JOIN, GROUP BY, HAVING, `FILTER`, `date_trunc` |
| C | 10-13 | CTE и window functions: running total, top-1-per-group, `lag`, доля в обороте |
| D | 14-17 | `ctid`, `xmin`/`xmax`, tuples на страницу, размеры отношений, `n_dead_tup` |
| E | 18-20 | инварианты, которые держит сама база: FK, список constraints, дубли idempotency key |

## Задания

1. Объяснить словами каждое PK/FK/UNIQUE/CHECK/NOT NULL в `sql/001_schema.sql`: какой конкретно инвариант ломается без него.
2. Найти строку по `ctid`, обновить её и показать, что `ctid` изменился, а логический `id` - нет.
3. Выполнить `UPDATE payments SET status = status` на всей таблице и измерить рост `pg_relation_size` и `n_dead_tup` до `VACUUM` и после.
4. Запрос 20 сейчас проверяет дубли idempotency key вручную. Добавить `UNIQUE (payment_id)` и убедиться, что база отвергает дубль сама.
5. Добавить платёж в валюте, которой нет у счёта, и объяснить, почему схема этого не запрещает и что нужно изменить.

## Что разобрать с ментором

- Какие инварианты обязаны защищаться базой, а какие допустимо оставить коду.
- Направление связей в ledger: почему `payment_id` nullable и что это значит для opening deposit.
- Почему пересчёт projection из ledger безопаснее, чем правка баланса рядом с записью проводки.

## Критерий готовности

- Можешь объяснить, почему `UPDATE` влияет на bloat и зачем нужен `VACUUM`.
- Можешь выбрать ключи и constraints без подсказки ORM.
- `drifted_accounts` и `unposted_payments` равны нулю, и ты можешь объяснить, каким порядком операций это достигнуто.

## Контрольные вопросы

- Почему `SELECT` может увидеть старую версию строки?
- Чем logical row отличается от physical tuple?
- Почему `ANALYZE` влияет на выбор плана?
- Почему `UPDATE` в PostgreSQL обычно создаёт новую версию строки, а не правит старую на месте?

## Материалы

- [PostgreSQL: Concurrency Control](https://www.postgresql.org/docs/17/mvcc.html) - MVCC и видимость версий строк.
- [PostgreSQL: Database Physical Storage](https://www.postgresql.org/docs/17/storage.html) - страницы, tuples, TID.
- [PostgreSQL: Routine Vacuuming](https://www.postgresql.org/docs/17/routine-vacuuming.html) - bloat, VACUUM и статистика.
