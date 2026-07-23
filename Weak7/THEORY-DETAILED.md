# Неделя 7 — подробная теория

Блокировки, дедлоки и финтех-корректность.

> Неделя 6 показала, что видит транзакция. Эта неделя — про то, как транзакции ждут друг друга, как это диагностировать и как построить перевод денег, который выдерживает 50 параллельных запросов и повторы после таймаута.

---

## 1. Блокировки уровня таблицы

### 1.1 Режимы и конфликты

Table-level блокировки берутся автоматически. Знать их нужно в первую очередь ради миграций (недели 8 и 13).

| Режим | Что берёт | Ключевой конфликт |
|---|---|---|
| `ACCESS SHARE` | `SELECT` | только с `ACCESS EXCLUSIVE` |
| `ROW SHARE` | `SELECT FOR UPDATE/SHARE` | `EXCLUSIVE`, `ACCESS EXCLUSIVE` |
| `ROW EXCLUSIVE` | `INSERT`, `UPDATE`, `DELETE` | `SHARE` и выше |
| `SHARE UPDATE EXCLUSIVE` | `VACUUM`, `ANALYZE`, `CREATE INDEX CONCURRENTLY`, часть `ALTER TABLE` | сам с собой |
| `SHARE` | `CREATE INDEX` (без CONCURRENTLY) | со всей записью |
| `SHARE ROW EXCLUSIVE` | некоторые `ALTER TABLE` | почти со всем |
| `EXCLUSIVE` | редко | со всем, кроме `ACCESS SHARE` |
| `ACCESS EXCLUSIVE` | `ALTER TABLE`, `DROP`, `TRUNCATE`, `VACUUM FULL`, `REINDEX` | **со всем**, включая `SELECT` |

### 1.2 Почему это важно для миграций

`ALTER TABLE ... ADD COLUMN ... NOT NULL DEFAULT ...` в современных версиях PostgreSQL быстр (значение по умолчанию хранится в каталоге), но многие другие `ALTER` требуют перезаписи таблицы под `ACCESS EXCLUSIVE`.

Отдельная ловушка — **очередь блокировок**. Если `ALTER TABLE` ждёт долгую транзакцию, он встаёт в очередь и **блокирует все следующие запросы**, даже обычные `SELECT`, которые сами по себе с ним не конфликтуют. Одна миграция может остановить сервис при полностью работоспособной базе.

Практика: в миграциях всегда ставить короткий `lock_timeout` и повторять попытку:

```sql
SET lock_timeout = '3s';
ALTER TABLE payments ADD COLUMN external_ref text;
```

---

## 2. Блокировки уровня строки

### 2.1 Режимы

| Конструкция | Семантика | Кто ещё берёт |
|---|---|---|
| `FOR UPDATE` | «я изменю строку, включая ключ» | `DELETE`, `UPDATE` ключевых колонок |
| `FOR NO KEY UPDATE` | «я изменю неключевые колонки» | обычный `UPDATE` |
| `FOR SHARE` | «строка не должна измениться» | — |
| `FOR KEY SHARE` | «ключ не должен измениться» | проверка FK при вставке в дочернюю таблицу |

Матрица конфликтов (упрощённо): `FOR UPDATE` конфликтует со всеми; `FOR NO KEY UPDATE` — со всеми, кроме `FOR KEY SHARE`; `FOR SHARE` — с `FOR UPDATE` и `FOR NO KEY UPDATE`; `FOR KEY SHARE` — только с `FOR UPDATE`.

Практический вывод: `FOR NO KEY UPDATE` заметно менее конфликтен, чем `FOR UPDATE`, и его достаточно, если вы меняете только баланс. Это же объясняет неожиданные блокировки: вставка строки с FK берёт `FOR KEY SHARE` на родительской строке, и параллельный `SELECT ... FOR UPDATE` на родителе будет ждать.

### 2.2 NOWAIT и SKIP LOCKED

```sql
SELECT * FROM accounts WHERE id = 1 FOR UPDATE NOWAIT;
-- ERROR: 55P03 could not obtain lock on row in relation "accounts"
```

`NOWAIT` — быстрый отказ вместо ожидания. Полезно для интерактивных операций, где лучше вернуть `409`, чем держать пользователя.

```sql
SELECT id FROM payments
WHERE status = 'NEW'
ORDER BY created_at
LIMIT 10
FOR UPDATE SKIP LOCKED;
```

`SKIP LOCKED` пропускает строки, заблокированные другими. Это канонический способ построить очередь задач на PostgreSQL без брокера: N воркеров разбирают разные задания, не мешая друг другу и не захватывая одно и то же дважды.

Обратите внимание на индекс: partial `ON payments (created_at) WHERE status = 'NEW'` (неделя 5) — именно под этот запрос.

### 2.3 Где хранятся row-locks

PostgreSQL не держит таблицу row-локов в памяти: признак блокировки записывается в сам tuple (`xmax` + флаги), а ожидание реализуется через блокировку на идентификаторе транзакции. Практическое следствие: **число заблокированных строк неограниченно** — нет эскалации блокировок, как в некоторых других СУБД. Но каждая блокировка — это запись в страницу, то есть I/O.

### 2.4 Advisory locks

```sql
SELECT pg_advisory_xact_lock(hashtext('transfer:' || :accountId));
```

Блокировка на произвольном числе, не привязанная к строке. Полезна, когда нужно сериализовать бизнес-операцию, у которой нет одной «главной» строки. `_xact_` версия освобождается на конце транзакции — предпочитайте её, сессионную легко забыть отпустить.

---

## 3. Optimistic против pessimistic

### 3.1 Сравнение

| | Optimistic (`version`) | Pessimistic (`FOR UPDATE`) |
|---|---|---|
| Механизм | проверка версии при записи | блокировка при чтении |
| Стоимость при отсутствии конфликта | нулевая | блокировка + ожидание других |
| Стоимость при конфликте | холостая работа, повтор/`409` | ожидание |
| Риск deadlock | нет (одна строка) | есть |
| Хорош, когда | конфликты редки, операция дешёвая | конфликты часты, операция дорогая |
| Взаимодействие с HTTP | естественный `409`/`If-Match` | клиент ждёт |

### 3.2 Практическое правило

Начинайте с самой дешёвой корректной конструкции:

1. **Атомарный `UPDATE ... WHERE условие`** — если операцию можно выразить одним оператором.
2. **Optimistic** — если нужно вычислять в приложении, а конфликты редки.
3. **Pessimistic** — если нужно согласованно читать и менять несколько строк.
4. **Serializable + retry** — если инвариант охватывает строки, которые вы не блокируете (write skew).

---

## 4. Deadlock

### 4.1 Как возникает

```
T1: BEGIN; UPDATE accounts SET ... WHERE id = 1;   -- держит строку 1
T2: BEGIN; UPDATE accounts SET ... WHERE id = 2;   -- держит строку 2
T1:        UPDATE accounts SET ... WHERE id = 2;   -- ждёт T2
T2:        UPDATE accounts SET ... WHERE id = 1;   -- ждёт T1  → цикл
```

Это ровно сценарий перевода A→B, выполняемого одновременно с переводом B→A.

### 4.2 Обнаружение

Транзакция, прождавшая `deadlock_timeout` (по умолчанию 1 с), запускает поиск цикла в графе ожиданий. Найден цикл → одна транзакция («жертва») откатывается:

```
ERROR:  deadlock detected
DETAIL: Process 123 waits for ShareLock on transaction 456; blocked by process 789.
        Process 789 waits for ShareLock on transaction 455; blocked by process 123.
SQLSTATE: 40P01
```

Важно: `deadlock_timeout` — не таймаут ожидания, а задержка перед проверкой. Обычный lock wait может длиться сколь угодно долго, пока его не оборвёт `lock_timeout`/`statement_timeout`.

### 4.3 Профилактика: единый порядок захвата

Главное средство — **всегда захватывать ресурсы в одном и том же детерминированном порядке**:

```sql
BEGIN;
SELECT id, balance_minor FROM accounts
WHERE id IN (:from, :to)
ORDER BY id
FOR NO KEY UPDATE;      -- обе строки заблокированы в порядке возрастания id
-- проверка баланса, две проводки, обновление балансов
COMMIT;
```

Ключевой момент: `ORDER BY id` в `SELECT ... FOR UPDATE` гарантирует, что все транзакции берут строки в одном порядке, и цикл ожидания становится невозможен.

Дополнительные меры: короткие транзакции; минимальное число блокируемых строк; отсутствие сетевых вызовов внутри; одинаковый порядок операций во всём коде.

### 4.4 Логирование

```
log_lock_waits = on
deadlock_timeout = 1s
```

С этими настройками в лог попадают и долгие ожидания, и полный контекст дедлоков. Число дедлоков — метрика, за которой следят (неделя 12); ноль дедлоков и один дедлок в неделю — разные состояния системы.

---

## 5. Диагностика

### 5.1 Кто кого блокирует

```sql
SELECT
    blocked.pid              AS blocked_pid,
    blocked.query            AS blocked_query,
    blocking.pid             AS blocking_pid,
    blocking.query           AS blocking_query,
    now() - blocked.query_start AS waiting_for
FROM pg_stat_activity blocked
JOIN LATERAL unnest(pg_blocking_pids(blocked.pid)) AS bpid ON true
JOIN pg_stat_activity blocking ON blocking.pid = bpid
WHERE blocked.wait_event_type = 'Lock';
```

`pg_blocking_pids(pid)` — самый короткий путь к ответу «кто виноват».

### 5.2 pg_locks

```sql
SELECT l.pid, l.locktype, l.mode, l.granted,
       c.relname, l.transactionid, left(a.query, 60) AS query
FROM pg_locks l
LEFT JOIN pg_class c ON c.oid = l.relation
LEFT JOIN pg_stat_activity a ON a.pid = l.pid
WHERE NOT l.granted OR l.locktype IN ('transactionid','tuple')
ORDER BY l.pid;
```

`granted = false` — строки, которые ждут. `locktype = 'transactionid'` — ожидание завершения чужой транзакции (то есть row-level конфликт).

### 5.3 Долгие транзакции

```sql
SELECT pid, state, now() - xact_start AS xact_age, wait_event_type, wait_event, left(query, 80)
FROM pg_stat_activity
WHERE xact_start IS NOT NULL AND now() - xact_start > interval '5 seconds'
ORDER BY xact_age DESC;
```

`state = 'idle in transaction'` при большом `xact_age` — приложение открыло транзакцию и занялось чем-то другим. Обычно это тот самый HTTP-вызов внутри транзакции.

### 5.4 Обязательные таймауты

```sql
ALTER ROLE app SET lock_timeout = '3s';
ALTER ROLE app SET statement_timeout = '10s';
ALTER ROLE app SET idle_in_transaction_session_timeout = '30s';
```

Без этих настроек одна зависшая транзакция способна остановить сервис. Для миграционной роли значения свои (см. неделю 13).

---

## 6. Идемпотентность

### 6.1 Постановка задачи

Клиент отправил `POST /transfers`, не дождался ответа (таймаут сети) и повторил запрос. Сервер должен: **не создать второй перевод** и вернуть результат первого.

### 6.2 Таблица и протокол

```sql
CREATE TABLE idempotency_keys (
    key            text PRIMARY KEY,
    user_id        bigint NOT NULL REFERENCES users(id),
    request_hash   bytea  NOT NULL,
    status         text   NOT NULL,          -- IN_PROGRESS | DONE
    response       jsonb,
    transfer_id    bigint REFERENCES transfers(id),
    created_at     timestamptz NOT NULL DEFAULT now()
);
```

Протокол обработки `POST /transfers` с заголовком `Idempotency-Key`:

```sql
BEGIN;

INSERT INTO idempotency_keys (key, user_id, request_hash, status)
VALUES (:key, :user, :hash, 'IN_PROGRESS')
ON CONFLICT (key) DO NOTHING;
-- 1 строка → мы первые, выполняем операцию
-- 0 строк → ключ уже есть

-- если 0 строк:
SELECT request_hash, status, response FROM idempotency_keys WHERE key = :key FOR UPDATE;
--   hash не совпал        → 422: тот же ключ с другим телом
--   status = 'DONE'       → вернуть сохранённый response (200/201)
--   status = 'IN_PROGRESS'→ 409 Conflict, "запрос уже обрабатывается"

-- если 1 строка: выполнить перевод, затем
UPDATE idempotency_keys SET status = 'DONE', response = :json, transfer_id = :id WHERE key = :key;

COMMIT;
```

### 6.3 Почему нужен UNIQUE

Наивная версия:

```sql
SELECT 1 FROM idempotency_keys WHERE key = :key;   -- нет
-- ... выполняем перевод ...
INSERT INTO idempotency_keys ...;
```

Два параллельных запроса оба увидят «нет» и оба выполнят перевод. Это TOCTOU в чистом виде. **UNIQUE-индекс переносит проверку в момент записи**, где она атомарна. Именно поэтому правило плана требует подкреплять идемпотентность constraint'ом.

### 6.4 Детали контракта

- **Ключ генерирует клиент** (UUID), сервер его не придумывает.
- **`request_hash`** защищает от повторного использования того же ключа с другим телом — иначе клиент может случайно «перезаписать» операцию.
- **Область действия ключа**: обычно (пользователь, endpoint). Ключ одного пользователя не должен влиять на другого.
- **Срок жизни**: ключи чистятся (например, старше 24-72 часов) — иначе таблица растёт вечно. Чистка — отдельная фоновая задача.
- **Ответ на повтор**: тот же код и то же тело, что и в первый раз. Некоторые API добавляют заголовок вроде `Idempotency-Replayed: true`.

---

## 7. Ledger

### 7.1 Модель

```sql
CREATE TABLE ledger_entries (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    transfer_id bigint NOT NULL REFERENCES transfers(id),
    account_id  bigint NOT NULL REFERENCES accounts(id),
    amount_minor bigint NOT NULL,          -- знак: <0 дебет, >0 кредит
    currency    char(3) NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ledger_amount_nonzero CHECK (amount_minor <> 0)
);
CREATE INDEX ON ledger_entries (account_id, created_at DESC, id DESC);
```

Принципы:

1. **Immutable.** Только `INSERT`. Никаких `UPDATE`/`DELETE`. Права на таблицу можно ограничить так, чтобы это было физически невозможно.
2. **Double-entry.** У каждой операции минимум две записи; сумма `amount_minor` по `transfer_id` равна нулю. Это проверяемый инвариант.
3. **Компенсация вместо удаления.** Отмена — новая проводка с обратным знаком и ссылкой на исходную.
4. **Баланс — projection.** `accounts.balance_minor` обновляется в той же транзакции, но истиной является сумма проводок:

   ```sql
   SELECT sum(amount_minor) FROM ledger_entries WHERE account_id = :id;
   ```

### 7.2 Зачем это, если можно один mutable balance

| | Один balance | Ledger + projection |
|---|---|---|
| Аудит «откуда взялась сумма» | невозможен | полный |
| Восстановление после бага | нет | пересчёт из проводок |
| Проверка инварианта | не с чем сверять | сверка projection и суммы |
| Отмена операции | правка баланса | компенсирующая проводка |
| Запись | `UPDATE` горячей строки | `INSERT` (не конфликтует) |
| Сложность | ниже | выше |

Для финтеха ledger — не архитектурное украшение, а условие возможности разобраться в инциденте.

### 7.3 Перевод целиком

```sql
BEGIN;

-- 1. Идемпотентность
INSERT INTO idempotency_keys (key, user_id, request_hash, status)
VALUES (:key, :user, :hash, 'IN_PROGRESS') ON CONFLICT (key) DO NOTHING;
-- 0 строк → отдать сохранённый результат, выйти

-- 2. Блокировка обоих счетов в едином порядке
SELECT id, balance_minor, currency FROM accounts
WHERE id IN (:from, :to) ORDER BY id FOR NO KEY UPDATE;

-- 3. Проверки: обе валюты совпадают, средств достаточно, счета активны

-- 4. Проводки
INSERT INTO transfers (from_account, to_account, amount_minor, currency, status)
VALUES (:from, :to, :amount, :cur, 'DONE') RETURNING id;

INSERT INTO ledger_entries (transfer_id, account_id, amount_minor, currency)
VALUES (:tid, :from, -:amount, :cur), (:tid, :to, :amount, :cur);

-- 5. Projection
UPDATE accounts SET balance_minor = balance_minor - :amount WHERE id = :from AND balance_minor >= :amount;
UPDATE accounts SET balance_minor = balance_minor + :amount WHERE id = :to;
-- проверить, что первый UPDATE затронул 1 строку

-- 6. Зафиксировать результат идемпотентности
UPDATE idempotency_keys SET status='DONE', response=:json, transfer_id=:tid WHERE key=:key;

COMMIT;
```

Плюс `CHECK (balance_minor >= 0)` в схеме как последний рубеж.

### 7.4 Инварианты, которые проверяет тест

1. Сумма всех `amount_minor` по одному `transfer_id` равна нулю.
2. Общая сумма денег в системе не меняется после N параллельных переводов.
3. `accounts.balance_minor` равен `sum(ledger_entries.amount_minor)` по счёту.
4. Ни один баланс не отрицателен.
5. Повторный `POST` с тем же ключом не создаёт вторую операцию.

---

## 8. Outbox (концепция)

Проблема: нужно и записать в БД, и отправить событие наружу. Две системы — атомарности нет.

- Отправить до коммита → транзакция откатилась, событие ушло: событие о несуществующей операции.
- Отправить после коммита → процесс упал между коммитом и отправкой: событие потеряно.

Решение — **transactional outbox**:

```sql
CREATE TABLE outbox (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    topic       text NOT NULL,
    payload     jsonb NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    sent_at     timestamptz
);
```

Запись в `outbox` идёт в **той же транзакции**, что и бизнес-данные. Отдельный процесс выбирает неотправленные (`FOR UPDATE SKIP LOCKED`), отправляет и помечает `sent_at`. Семантика — **at-least-once**, поэтому потребитель обязан быть идемпотентным. Подробнее — неделя 11.

---

## 9. Лаборатория недели

1. Создать дедлок двумя транзакциями (`locks-session-a.sql` / `locks-session-b.sql`), сохранить SQL и timeline, показать текст `40P01`.
2. Исправить его единым порядком блокировки счетов; повторить сценарий и показать, что дедлока нет.
3. Реализовать перевод с проверкой баланса, ledger и projection.
4. Добавить `Idempotency-Key` и тест повторного `POST` после таймаута.
5. Запустить 50 параллельных переводов и проверить инвариант общей суммы.
6. Показать `SKIP LOCKED` на очереди задач: два воркера, ни одного двойного захвата.
7. Разобрать `pg_locks` и `pg_stat_activity` во время искусственно удержанной блокировки.
8. Проверить, что происходит без `lock_timeout` и с ним.

---

## 10. Типичные ошибки недели

1. Блокировка счетов в порядке «сначала отправитель, потом получатель» — прямой путь к дедлоку.
2. Идемпотентность без UNIQUE constraint.
3. `SELECT FOR UPDATE` без `lock_timeout`.
4. Retry дедлока без идемпотентности → двойной перевод.
5. `UPDATE` ledger-записей («поправим проводку»).
6. Баланс как единственный источник истины.
7. Проверка баланса в приложении без `CHECK` в схеме.
8. Тест на одном потоке и вывод «работает».
9. Внешний вызов внутри транзакции, держащей блокировки на счетах.
10. `FOR UPDATE` там, где достаточно `FOR NO KEY UPDATE`.

---

## 11. Критерий готовности

- Параллельный тест на 50 переводов не нарушает общий баланс и не создаёт двойную операцию.
- Можете воспроизвести дедлок, а не только дать определение, и показать его устранение.
- Умеете найти блокирующую сессию через `pg_blocking_pids`/`pg_locks`.
- Знаете, где retry безопасен, и как логируются `40001`/`40P01`.
- Ledger неизменяем, projection сходится с суммой проводок.

## 12. Официальные материалы

- PostgreSQL: Chapter 13.3 — Explicit Locking (table-level, row-level, deadlocks, advisory locks).
- PostgreSQL: `SELECT ... FOR UPDATE / FOR NO KEY UPDATE / FOR SHARE / FOR KEY SHARE / SKIP LOCKED / NOWAIT`.
- PostgreSQL: `pg_locks`, `pg_stat_activity`, `pg_blocking_pids`.
- PostgreSQL: Chapter 20.11 — `lock_timeout`, `deadlock_timeout`, `statement_timeout`.
- Stripe API Reference — Idempotent Requests (как контракт).
