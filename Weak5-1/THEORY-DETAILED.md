# Неделя 5-1 — подробная теория (Spring API + EXPLAIN ANALYZE)

Составные и специальные индексы со стороны приложения: как контракт endpoint'а определяет план выполнения, и как держать этот план под контролем в коде и в CI.

Теория индексов подробно разобрана в [Weak5/THEORY-DETAILED.md](../Weak5/THEORY-DETAILED.md). Здесь — минимум и прикладная часть.

---

## 1. Минимум по составным индексам

- Индекс `(a, b, c)` — B-tree по кортежу, отсортированный лексикографически.
- **Leftmost prefix**: индекс сужает поиск, пока предикаты идут слева направо без пропусков. `WHERE b = ?` по индексу `(a, b)` не сужает ничего.
- Порядок колонок: **равенство → одна колонка диапазона → сортировка**. После диапазонной колонки сужение прекращается.
- `INCLUDE (...)` кладёт колонки только в листья: они доступны для чтения, но не для поиска и сортировки.
- **Index Only Scan** требует и покрытия колонок, и отметки страницы в visibility map (`Heap Fetches: 0`).
- **Bitmap Scan** объединяет несколько индексов (`BitmapAnd`/`BitmapOr`) и читает heap по возрастанию страниц; порядок теряется.
- **Partial** индекс применяется, если предикат запроса следует из предиката индекса; **expression** — если выражение совпадает буквально.

---

## 2. От endpoint'а к индексу

### 2.1 Метод: начинать с запроса, а не с индекса

Правило плана: индекс не добавляется «на всякий случай». Порядок работы:

1. Взять конкретный endpoint и его реальный SQL.
2. Собрать данные боевого объёма и распределения.
3. Снять `EXPLAIN (ANALYZE, BUFFERS)` — это «план до».
4. Сформулировать гипотезу: какие колонки равенства, какая диапазонная, какая сортировка.
5. Создать индекс, снять «план после».
6. Замерить цену: размер индекса и замедление записи.
7. Записать всё в таблицу PR (без неё ментор не смотрит индекс).

### 2.2 Разбор типичных endpoint'ов

**История платежей**

```sql
SELECT id, created_at, amount_minor, status
FROM payments
WHERE account_id = :acc AND created_at >= :from AND created_at < :to
ORDER BY created_at DESC
LIMIT 50;
```

Индекс: `(account_id, created_at DESC)`. Добавление `INCLUDE (amount_minor, status)` даёт Index Only Scan для read-heavy сценария.

**Keyset-пагинация ledger**

```sql
SELECT id, created_at, amount_minor
FROM ledger_entries
WHERE account_id = :acc AND (created_at, id) < (:ts, :id)
ORDER BY created_at DESC, id DESC
LIMIT 20;
```

Индекс: `(account_id, created_at DESC, id DESC)`. Кортежное сравнение точно ложится на индекс; развёрнутая форма `a < x OR (a = x AND b < y)` — хуже.

**Очередь обработки**

```sql
SELECT id FROM payments
WHERE status = 'NEW'
ORDER BY created_at
LIMIT 10
FOR UPDATE SKIP LOCKED;      -- неделя 7
```

Индекс: partial `ON payments (created_at) WHERE status = 'NEW'`. Он крошечный, полностью в кеше, и не платит за строки в терминальных статусах.

**Логин**

```sql
SELECT id, password_hash FROM users WHERE lower(email) = lower(:email);
```

Индекс: `ON users (lower(email))`, желательно `UNIQUE` — он же выражает инвариант «email уникален без учёта регистра». Приложение обязано использовать ровно это выражение.

**Поиск по метаданным**

```sql
SELECT * FROM payments WHERE metadata @> :filter::jsonb;
```

Индекс: `USING gin (metadata jsonb_path_ops)`. Помните про цену записи GIN.

### 2.3 Контракт API как обязательство перед индексом

Каждый параметр фильтрации и сортировки, который вы обещаете в API, — это обязательство поддержать соответствующий план. Практические следствия:

- не обещайте произвольные комбинации фильтров;
- сортировку разрешайте только по полям из белого списка, для которых есть индексы;
- глубокую offset-пагинацию не обещайте вовсе — давайте курсор.

---

## 3. Получение и проверка планов из приложения

### 3.1 EXPLAIN через JdbcTemplate

```kotlin
fun explain(sql: String, params: Map<String, Any?>): String =
    jdbc.queryForList("EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT) $sql", params)
        .joinToString("\n") { it.values.first().toString() }
```

Правила:

- `EXPLAIN ANALYZE` **выполняет** запрос. Для `INSERT`/`UPDATE`/`DELETE` оборачивайте в транзакцию и делайте `ROLLBACK`.
- Подставляйте те же параметры, что в проде: план для `status='FAILED'` (1% строк) и `status='DONE'` (90%) — разные.
- `FORMAT JSON` удобен для автоматической проверки в тестах.

### 3.2 Тест, защищающий план

Реалистичный приём для критичных запросов: интеграционный тест на Testcontainers (неделя 10), который наполняет таблицу, делает `ANALYZE` и проверяет, что план **не содержит** `Seq Scan` по большой таблице:

```kotlin
val plan = explain(HISTORY_SQL, params)
assertThat(plan).contains("Index Scan").doesNotContain("Seq Scan on payments")
```

Такой тест хрупок, если писать его слишком строго (версия PostgreSQL может изменить формулировку узла). Проверяйте узкий инвариант: отсутствие полного сканирования конкретной большой таблицы или `Heap Fetches: 0`.

### 3.3 Поиск кандидатов

- `log_min_duration_statement = 200ms` — всё, что дольше, попадает в лог PostgreSQL.
- `pg_stat_statements` — агрегаты по нормализованным запросам:

  ```sql
  SELECT calls, round(mean_exec_time::numeric, 2) AS mean_ms,
         round(total_exec_time::numeric) AS total_ms, rows, query
  FROM pg_stat_statements
  ORDER BY total_exec_time DESC LIMIT 20;
  ```

  Сортировка по `total_exec_time`, а не по `mean_exec_time`: запрос на 5 мс, вызываемый миллион раз, важнее запроса на 2 с, вызываемого раз в час.
- На стороне приложения — логировать длительность запроса вместе с `requestId` (неделя 12).

---

## 4. Prepared statements, generic plan и skew

### 4.1 Что происходит

JDBC-драйвер PostgreSQL после `prepareThreshold` (по умолчанию 5) выполнений одного и того же SQL переходит на серверный prepared statement. PostgreSQL после нескольких выполнений с custom plan может решить, что **generic plan** (без учёта конкретных значений параметров) не хуже, и переключиться на него.

Для равномерных данных это выигрыш — экономия на планировании. Для данных со skew это ловушка: generic plan строится по средней селективности, а реальный параметр может отличаться на два порядка.

### 4.2 Симптомы и лечение

Симптом: запрос быстрый в `psql` с литералом и медленный из приложения; в логе видны разные времена для одного SQL.

Инструменты:

- `SET plan_cache_mode = force_custom_plan;` (сессия или роль/база) — всегда планировать под конкретные параметры;
- параметр драйвера `prepareThreshold=0` — отключить серверные prepared statements;
- изменить сам запрос так, чтобы skew ушёл (например, разделить endpoint на два — «активные» и «архивные»).

Отдельно: при использовании пула соединений через внешний pooler (PgBouncer в transaction mode) серверные prepared statements требуют аккуратной настройки.

---

## 5. Проверки, которые стоит держать в проекте

### 5.1 Неиспользуемые и дублирующие индексы

```sql
-- неиспользуемые
SELECT relname, indexrelname, idx_scan, pg_size_pretty(pg_relation_size(indexrelid))
FROM pg_stat_user_indexes WHERE idx_scan = 0
ORDER BY pg_relation_size(indexrelid) DESC;
```

Дублирующим считается индекс, чей набор колонок является префиксом другого: при наличии `(a, b, c)` индекс `(a, b)` почти всегда лишний (исключение — если он `UNIQUE` или существенно меньше и обслуживает горячий запрос).

### 5.2 Размеры

```sql
SELECT relname,
       pg_size_pretty(pg_relation_size(relid))       AS heap,
       pg_size_pretty(pg_indexes_size(relid))        AS indexes,
       pg_size_pretty(pg_total_relation_size(relid)) AS total
FROM pg_catalog.pg_statio_user_tables ORDER BY pg_total_relation_size(relid) DESC;
```

Когда `indexes` заметно больше `heap`, это повод пересмотреть набор индексов, а не гордиться покрытием.

### 5.3 Создание индексов в миграциях

На боевой таблице — только `CREATE INDEX CONCURRENTLY`:

- не блокирует запись, но идёт в два прохода и дольше;
- **не работает внутри транзакции** — во Flyway это требует отдельной миграции с отключённой транзакцией (неделя 8);
- при сбое остаётся невалидный индекс: проверить `pg_index.indisvalid`, удалить и повторить.

### 5.4 Статистика и расширенная статистика

После массовой загрузки в CI/тестах обязателен `ANALYZE`, иначе планы бессмысленны. Для коррелирующих колонок:

```sql
CREATE STATISTICS payments_acc_status (dependencies, ndistinct) ON account_id, status FROM payments;
ANALYZE payments;
```

---

## 6. Лаборатория недели

1. Реализовать `GET /api/accounts/{id}/payments` с фильтром по периоду и статусу; снять план до индексов.
2. Сравнить `(account_id, created_at)` и `(created_at, account_id)`; показать разницу по `Rows Removed by Filter` и `Buffers`.
3. Добавить `INCLUDE` и добиться `Heap Fetches: 0`; затем сделать массовый UPDATE и показать, как `Heap Fetches` вырос до `VACUUM`.
4. Partial index под endpoint очереди; сравнить размер с полным индексом на `status`.
5. Expression index под логин; показать, что запрос без `lower()` его не использует.
6. Воспроизвести проблему generic plan на колонке со skew.
7. Замерить latency `POST /api/payments` с 0 / 1 / несколькими индексами.
8. Оформить итоговую таблицу: запрос → план до → индекс → план после → buffers → цена записи.

---

## 7. Типичные ошибки недели

1. Индекс подобран «по логике», без плана до и после.
2. Планы сняты на маленькой тестовой базе.
3. Ожидание Index Only Scan без `VACUUM`.
4. Запрос в коде не совпадает с выражением expression index.
5. Partial index, чьё условие приложение не всегда добавляет в `WHERE`.
6. Глубокий `OFFSET` в контракте API.
7. `EXPLAIN` без `ANALYZE` и вывод по одним оценкам.
8. `CREATE INDEX` без `CONCURRENTLY` в миграции на живой таблице.

---

## 8. Критерий готовности

- Для каждого индекса называете конкретный endpoint и SQL, который он ускоряет, и операции, которые он замедляет.
- Объясняете leftmost behavior причинно.
- Читаете `EXPLAIN (ANALYZE, BUFFERS)` и находите расхождение оценки и факта.
- Показываете случай, когда планировщик сознательно не использует индекс, и объясняете, почему он прав.
- Знаете, как ваш код взаимодействует с prepared statements и generic plan.

## 9. Официальные материалы

- PostgreSQL: Chapter 11 — Multicolumn/Unique/Partial/Expression Indexes, Index-Only Scans, Examining Index Usage.
- PostgreSQL: Chapter 14.1-14.2 — Using EXPLAIN, Statistics Used by the Planner.
- PostgreSQL: `CREATE STATISTICS`, `CREATE INDEX ... CONCURRENTLY`, `pg_stat_statements`.
- PostgreSQL JDBC Driver — Server Prepared Statements (`prepareThreshold`).
