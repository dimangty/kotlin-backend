# Неделя 3 — подробная теория (SQL-first)

PostgreSQL: схема, SQL и физическое хранение данных.

> Ключевая мысль недели: таблица — это не список объектов. Это последовательность страниц по 8 КБ, в которых лежат **версии** строк. Почти всё, что покажется странным на неделях 4-7, объясняется этим фактом.

---

## 1. Реляционная модель и проектирование схемы

### 1.1 Ключи

**Primary key** — минимальный набор колонок, уникально идентифицирующий строку, `NOT NULL`. Выбор типа:

| Тип | Плюсы | Минусы |
|---|---|---|
| `bigserial` / `identity` | компактный (8 байт), монотонный → плотная запись в индекс | предсказуем (утечка объёма бизнеса), требует координации при слиянии данных |
| `uuid` v4 | генерируется клиентом, не раскрывает объём | 16 байт, **случайный** → случайные вставки в B-tree, page splits, распухание индекса (увидим на неделе 4) |
| `uuid` v7 / ULID | генерируется клиентом и при этом монотонный по времени | относительно новый, нужна поддержка на стороне приложения |

Natural key (`email`) как PK — почти всегда ошибка: значение меняется, а FK на него — нет.

**Foreign key** задаёт направление зависимости и не даёт появиться «висячей» ссылке. Поведение при удалении родителя выбирается осознанно:

- `ON DELETE RESTRICT` (по умолчанию `NO ACTION`) — для финансовых данных умолчание: ledger нельзя удалять вместе с пользователем;
- `ON DELETE CASCADE` — только для строго подчинённых сущностей;
- `ON DELETE SET NULL` — когда связь опциональна.

Важно: **PostgreSQL не создаёт индекс на referencing-колонку автоматически**. Без него `DELETE` родителя приводит к последовательному сканированию дочерней таблицы, а на неделе 7 — к неожиданным блокировкам.

### 1.2 Ограничения как инварианты

```sql
CREATE TABLE accounts (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id         bigint NOT NULL REFERENCES users(id),
    currency        char(3) NOT NULL,
    balance_minor   bigint NOT NULL DEFAULT 0,
    status          text   NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT accounts_balance_non_negative CHECK (balance_minor >= 0),
    CONSTRAINT accounts_status_valid CHECK (status IN ('ACTIVE','FROZEN','CLOSED'))
);

CREATE TABLE idempotency_keys (
    key         text PRIMARY KEY,
    user_id     bigint NOT NULL REFERENCES users(id),
    request_hash bytea NOT NULL,
    response    jsonb,
    created_at  timestamptz NOT NULL DEFAULT now()
);
```

Правило недели: **инвариант, нарушение которого нельзя допустить ни при каких обстоятельствах, должен быть выражен ограничением базы**. Причины:

1. Приложение работает в нескольких инстансах — проверка «в коде» проверяет разные копии состояния.
2. Между `SELECT` и `INSERT` проходит время, за которое состояние меняется (TOCTOU). Constraint проверяется в момент записи.
3. В базу ходят миграции, скрипты поддержки и админы; код их не покрывает.

Что оставить приложению: правила с хорошим текстом ошибки, правила, зависящие от внешних данных, и всё, что дорого проверять в БД.

`CHECK` не может ссылаться на другие строки и таблицы. «Сумма ledger по счёту равна balance» — не `CHECK`; такие инварианты держатся транзакцией и тестами (недели 7 и 10).

### 1.3 Учебная схема

Пять таблиц, к которым мы вернёмся во всех оставшихся неделях:

- `users` — владелец;
- `accounts` — счёт с валютой, статусом и **projection** баланса;
- `payments` — заявка/операция с внешним статусом;
- `ledger_entries` — **неизменяемые** проводки (double-entry: у перевода две записи, дебет и кредит, сумма по операции равна нулю);
- `idempotency_keys` — защита от повторной обработки.

Почему и ledger, и `balance_minor`: ledger — источник истины (append-only, аудируемый), баланс — денормализованная проекция для быстрых чтений. Расхождение между ними (drift) — то, что лаборатория заставляет увидеть и закрыть.

### 1.4 Нормализация и деньги

3NF как умолчание: не хранить производное и не дублировать факты. Денормализация допустима, если есть механизм поддержания согласованности в той же транзакции.

Деньги: `bigint` в минорных единицах (копейки/центы) либо `numeric`. `float8` запрещён — `0.1 + 0.2 != 0.3`, и ошибка накапливается. Валюта хранится рядом с суммой; складывать суммы в разных валютах нельзя даже случайно (защищается `CHECK`/схемой).

Время: `timestamptz`, всегда. `timestamp` без зоны хранит «неизвестно что» и ломается при смене таймзоны сервера.

---

## 2. Физическое хранение

### 2.1 Страницы и tuples

Таблица (**heap**) — файл, разбитый на страницы по 8 КБ. Структура страницы:

```
+-------------------------------------------------+
| PageHeader (24 байта)                           |
| ItemId[1] ItemId[2] ItemId[3] ...  → растут вниз |
|                                                 |
|             свободное место                     |
|                                                 |
| ... Tuple[3] Tuple[2] Tuple[1]     ← растут вверх|
+-------------------------------------------------+
```

**Tuple** = header (~23 байта, включая `xmin`, `xmax`, флаги) + null bitmap + данные колонок. Строка не может пересекать границу страницы — отсюда TOAST.

**`ctid`** — пара `(номер страницы, номер item)`, физический адрес версии строки:

```sql
SELECT ctid, xmin, xmax, id, balance_minor FROM accounts WHERE id = 1;
```

`ctid` не является идентификатором строки: он меняется при каждом UPDATE и после `VACUUM FULL`. Использовать его в приложении нельзя; смотреть на него, чтобы понять MVCC — обязательно.

### 2.2 TOAST

Значения, не помещающиеся в страницу (порог около 2 КБ), сжимаются и/или выносятся в связанную таблицу `pg_toast.pg_toast_<oid>`. Практические следствия: большие `text`/`jsonb` не раздувают основной heap, но каждое обращение к ним — дополнительное чтение; `SELECT *` по таблице с TOAST-полями заметно дороже выборки нужных колонок.

### 2.3 Порядок колонок и выравнивание

Колонки выравниваются по границам типов, поэтому чередование `bigint, boolean, bigint, boolean` тратит больше места, чем `bigint, bigint, boolean, boolean`. На миллионах строк это единицы процентов размера таблицы — не первое, чем стоит заниматься, но знать полезно.

---

## 3. MVCC: почему UPDATE создаёт новую версию

### 3.1 Механика

PostgreSQL не изменяет tuple на месте. `UPDATE`:

1. записывает **новую** версию строки (обычно в ту же страницу, если есть место, иначе в другую);
2. в старой версии проставляет `xmax = <id текущей транзакции>`;
3. добавляет записи во **все** индексы таблицы, указывающие на новый `ctid` (исключение — HOT-update, см. 3.4).

`DELETE` только проставляет `xmax`. Физически ничего не удаляется до `VACUUM`.

Эксперимент, который надо проделать руками:

```sql
SELECT ctid, xmin, xmax FROM accounts WHERE id = 1;
UPDATE accounts SET balance_minor = balance_minor + 100 WHERE id = 1;
SELECT ctid, xmin, xmax FROM accounts WHERE id = 1;   -- другой ctid, новый xmin
```

### 3.2 Snapshot и видимость

Транзакция получает **snapshot**: `xmin` (всё, что старше — завершено), `xmax` (всё, что новее — ещё не началось) и список активных транзакций. Версия строки видима, если её создавшая транзакция закоммичена и видна в snapshot, а удалившая — нет.

Момент взятия snapshot зависит от уровня изоляции (неделя 6):

- **Read Committed** — новый snapshot на **каждый** оператор;
- **Repeatable Read / Serializable** — один snapshot на всю транзакцию.

Отсюда прямой ответ на контрольный вопрос «почему SELECT видит старую версию»: он смотрит на состояние, зафиксированное его snapshot'ом, а не на «сейчас».

### 3.3 Следствия для практики

1. **Читатели не блокируют писателей и наоборот.** Долгий `SELECT` не мешает `UPDATE` — принципиальное отличие от блокировочных СУБД.
2. **Bloat.** Мёртвые версии занимают место, пока их не уберёт VACUUM. Таблица с интенсивными UPDATE растёт быстрее, чем растут данные.
3. **Долгая транзакция вредна всем.** Пока она жива, VACUUM не может удалить версии, которые ей могут понадобиться. Одна забытая открытая транзакция раздувает базу.
4. **UPDATE одной колонки переписывает всю строку** и обновляет все индексы — цена индексов платится не только на INSERT (неделя 4).

### 3.4 HOT-update

Если изменённые колонки **не входят ни в один индекс** и новая версия помещается **в ту же страницу**, PostgreSQL делает Heap-Only Tuple update: новая версия связывается со старой цепочкой внутри страницы, индексы не трогаются. Это дёшево. Управляется `fillfactor` (например, 80 — оставить 20% страницы под будущие версии). Практический вывод: индексировать часто обновляемые колонки дорого вдвойне.

---

## 4. WAL, checkpoint, VACUUM, ANALYZE

### 4.1 WAL

Write-Ahead Logging: любое изменение сначала попадает в журнал, и только потом — на страницы данных. Коммит считается завершённым, когда WAL-запись сброшена на диск (`fsync`). Это и есть **D** из ACID.

Следствия: WAL — узкое место записи; каждый лишний индекс генерирует дополнительный WAL; `synchronous_commit = off` ускоряет запись ценой возможной потери последних транзакций при сбое (для финтеха — нет).

### 4.2 Checkpoint

Периодически грязные страницы из shared buffers сбрасываются на диск, и в WAL ставится отметка: восстановление после сбоя начинается с неё. Редкие checkpoint'ы → долгое восстановление; частые → всплески I/O.

### 4.3 VACUUM

Что делает:

1. помечает место, занятое dead tuples, как переиспользуемое;
2. обновляет **visibility map** — карту страниц, где все версии видимы всем (от неё зависит Index Only Scan, неделя 5);
3. предотвращает **transaction ID wraparound** (счётчик транзакций 32-битный и зацикливается);
4. `VACUUM ANALYZE` — заодно обновляет статистику.

Обычный `VACUUM` **не отдаёт место операционной системе** и не блокирует чтение/запись. `VACUUM FULL` переписывает таблицу целиком, отдаёт место, но берёт `ACCESS EXCLUSIVE` — на живой системе это простой.

Autovacuum срабатывает по порогам (`autovacuum_vacuum_scale_factor` и т.п.). На больших горячих таблицах пороги по умолчанию слишком велики — настраиваются на уровне таблицы.

Наблюдение:

```sql
SELECT relname, n_live_tup, n_dead_tup, last_vacuum, last_autovacuum
FROM pg_stat_user_tables ORDER BY n_dead_tup DESC;
```

### 4.4 ANALYZE

Собирает статистику в `pg_statistic` (читаемое представление — `pg_stats`): доля NULL, `n_distinct`, most common values и их частоты, гистограмма, корреляция физического порядка с логическим.

```sql
SELECT attname, n_distinct, null_frac, correlation
FROM pg_stats WHERE tablename = 'payments';
```

Планировщик **не смотрит на данные** — он смотрит на эту статистику. Отсюда: сразу после массовой загрузки план может быть катастрофическим, пока не выполнен `ANALYZE`. Это первое, что нужно проверить, когда «запрос вдруг стал медленным».

---

## 5. SQL, который нужно написать руками

Правило плана: **не переходить к ORM, пока 20 запросов к учебной схеме не написаны на SQL.** Не потому, что ORM плох, а потому что иначе вы не сможете прочитать то, что он генерирует (неделя 8).

### 5.1 JOIN

```sql
SELECT a.id, u.email, a.balance_minor
FROM accounts a
JOIN users u ON u.id = a.user_id
WHERE a.status = 'ACTIVE';
```

Классическая ошибка:

```sql
SELECT ... FROM accounts a
LEFT JOIN payments p ON p.account_id = a.id
WHERE p.status = 'DONE';        -- LEFT JOIN превратился в INNER
```

Условие на правую таблицу должно быть в `ON`, если вы хотите сохранить внешнее соединение.

Алгоритмы соединения (пригодятся при чтении планов на неделе 5): **Nested Loop** — хорош, когда внешняя выборка мала и есть индекс на внутренней; **Hash Join** — когда одна сторона помещается в память; **Merge Join** — когда обе стороны отсортированы.

### 5.2 Агрегаты

```sql
SELECT account_id, count(*) AS payments, sum(amount_minor) AS total
FROM payments
WHERE created_at >= now() - interval '30 days'
GROUP BY account_id
HAVING sum(amount_minor) > 1000000;
```

`WHERE` фильтрует строки **до** группировки, `HAVING` — группы **после**. `count(*)` считает строки, `count(col)` пропускает NULL, `count(DISTINCT col)` дорог.

NULL: `NULL = NULL` даёт `NULL`, а не `TRUE`; используйте `IS NULL` / `IS DISTINCT FROM`. Агрегаты игнорируют NULL, кроме `count(*)`.

### 5.3 Window functions

```sql
SELECT
    created_at,
    amount_minor,
    sum(amount_minor) OVER (PARTITION BY account_id ORDER BY created_at, id) AS running_balance,
    row_number()      OVER (PARTITION BY account_id ORDER BY created_at DESC) AS rn
FROM ledger_entries;
```

Отличие от `GROUP BY`: строки не схлопываются, агрегат вычисляется по «окну» вокруг каждой строки. Это и есть способ показать running balance по ledger — то, что понадобится на неделе 16.

### 5.4 CTE

```sql
WITH recent AS (
    SELECT * FROM payments WHERE created_at >= now() - interval '7 days'
)
SELECT status, count(*) FROM recent GROUP BY status;
```

С PostgreSQL 12 CTE по умолчанию **inlined** (планировщик может «протолкнуть» условия внутрь). `MATERIALIZED` заставляет вычислить один раз — иногда это нужно, иногда это оптимизационный барьер, ухудшающий план.

`INSERT ... RETURNING` и data-modifying CTE — способ сделать вставку и получить сгенерированный id одним запросом; понадобится для ledger.

---

## 6. Лаборатория: 100 000 строк

Генерация:

```sql
INSERT INTO payments (account_id, amount_minor, status, created_at)
SELECT
    1 + (random() * 999)::int,
    (random() * 100000)::bigint,
    (ARRAY['NEW','DONE','FAILED'])[1 + (random() * 2)::int],
    now() - (random() * interval '365 days')
FROM generate_series(1, 100000);
```

Что смотреть после:

```sql
SELECT pg_size_pretty(pg_relation_size('payments'))  AS heap,
       pg_size_pretty(pg_indexes_size('payments'))   AS indexes,
       pg_size_pretty(pg_total_relation_size('payments')) AS total;

SELECT count(*) FROM payments;
SELECT reltuples::bigint FROM pg_class WHERE relname = 'payments';  -- оценка планировщика
```

Расхождение между `count(*)` и `reltuples` до `ANALYZE` — наглядная демонстрация того, что планировщик работает с оценками.

Затем — сценарий bloat:

```sql
UPDATE payments SET status = 'DONE' WHERE status = 'NEW';
SELECT n_live_tup, n_dead_tup FROM pg_stat_user_tables WHERE relname = 'payments';
VACUUM (VERBOSE, ANALYZE) payments;
SELECT n_live_tup, n_dead_tup FROM pg_stat_user_tables WHERE relname = 'payments';
```

---

## 7. Типичные ошибки недели

1. Деньги в `double precision`.
2. `timestamp` вместо `timestamptz`.
3. FK без индекса на дочерней колонке.
4. Инвариант проверяется только в приложении («мы же всегда проверяем перед вставкой»).
5. `status` как свободный `text` без `CHECK` — со временем в таблице появляются `done`, `DONE`, `Done`.
6. Замер производительности на 10 строках: план на маленькой таблице всегда Seq Scan, и это правильно.
7. `SELECT *` в приложении: ломается при добавлении колонки, тянет TOAST-поля.

---

## 8. Критерий готовности

- Объясняете, почему UPDATE влияет на bloat и зачем нужен VACUUM.
- Выбираете ключи и constraints без подсказки ORM и обосновываете каждый.
- Показываете `ctid`/`xmin` до и после UPDATE и комментируете изменение.
- Написали не меньше 20 запросов, включая JOIN, GROUP BY/HAVING, CTE и window function.
- Можете сказать, какой инвариант защищён на каком уровне: DTO, домен, транзакция, constraint.

## 9. Официальные материалы

- PostgreSQL: Chapter 5 — Data Definition (constraints).
- PostgreSQL: Chapter 13 — Concurrency Control (MVCC, snapshots).
- PostgreSQL: Chapter 24 — Routine Vacuuming; Chapter 30 — WAL.
- PostgreSQL: Chapter 73 — Database Physical Storage (page layout, TOAST, HOT).
- PostgreSQL: Chapter 14.2 — Statistics Used by the Planner.
