# Неделя 5-1 — краткая теория (Spring API + EXPLAIN)

Та же неделя, что и [Weak5](../Weak5/), но со стороны приложения: как endpoint превращается в план выполнения и как это контролировать.

---

## 1. Составной индекс — минимум

Индекс `(a, b, c)` отсортирован лексикографически. Применяется слева направо без пропусков (**leftmost**).

Порядок колонок: **равенство → один диапазон → сортировка**.

```
GET /api/accounts/{id}/payments?from=&to=&sort=created_at desc
WHERE account_id = ? AND created_at BETWEEN ? AND ? ORDER BY created_at DESC
→ (account_id, created_at DESC)
```

`(created_at, account_id)` для этого запроса плох: читает всех пользователей за период и фильтрует.

## 2. Covering / Index Only Scan

- `INCLUDE (amount_minor, status)` кладёт колонки в листья без участия в поиске.
- Index Only Scan требует ещё и свежую **visibility map** → в плане `Heap Fetches: 0`.
- После интенсивной записи index-only «ломается» до следующего `VACUUM`.

## 3. Partial и expression

```sql
CREATE INDEX ON payments (created_at) WHERE status = 'NEW';  -- очередь обработки
CREATE UNIQUE INDEX ON accounts (user_id, currency) WHERE status <> 'CLOSED';
CREATE INDEX ON users (lower(email));                        -- login по email
```

Приложение обязано писать запрос **тем же выражением** (`WHERE lower(email) = lower(:email)`), иначе индекс не сработает.

## 4. Как получить план из приложения

- `EXPLAIN (ANALYZE, BUFFERS)` через `JdbcTemplate` на модифицирующем запросе — только внутри транзакции с `ROLLBACK`.
- `log_min_duration_statement` в PostgreSQL и `pg_stat_statements` для поиска кандидатов.
- Запрос должен быть параметризованным и в EXPLAIN тоже — иначе вы измеряете не то, что выполняется в проде.

## 5. Prepared statements и generic plan

JDBC после нескольких выполнений может перейти на **generic plan** — план без учёта конкретных значений. При сильном skew (`status='FAILED'` — 1%, `status='DONE'` — 90%) это даёт плохой план.

- Диагностика: `EXPLAIN` с литералом против плана в логе.
- Управление: `plan_cache_mode = force_custom_plan`, `prepareThreshold` в JDBC-драйвере.

## 6. Чек-лист чтения плана

1. `rows` оценка против факта — расхождение в разы это корень проблемы.
2. `Rows Removed by Filter` — прочитано зря.
3. `Heap Fetches` в Index Only Scan.
4. `Sort Method: external merge Disk` — не хватило `work_mem`.
5. `Buffers: shared read` — реальный I/O.
6. `loops` у Nested Loop — умножайте `actual time`.

---

## Контрольные вопросы

1. **Почему `(status, user_id)` и `(user_id, status)` неэквивалентны?** Индекс сужает поиск только по ведущим колонкам; запрос по одному `user_id` не может воспользоваться первым.
2. **Почему нужный индекс есть, а план его не берёт?** Устаревшая статистика, слишком большая доля выборки, несовпадение выражения, generic plan или неподходящий порядок колонок.
3. **Почему `Heap Fetches` вырос после релиза?** Была массовая запись/обновление, visibility map устарела, `VACUUM` ещё не прошёл.
4. **Что делать при расхождении оценки и факта из-за коррелирующих колонок?** `CREATE STATISTICS (dependencies, ndistinct) ON a, b FROM t` и `ANALYZE`.
