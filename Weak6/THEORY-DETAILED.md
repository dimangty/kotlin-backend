# Неделя 6 — подробная теория (SQL-first)

Транзакции, ACID, MVCC и уровни изоляции.

> Правило недели: **пока аномалия не воспроизведена руками в двух сессиях, определение считается неусвоенным.** Всё в этом файле нужно проверить на `session-a.sql` / `session-b.sql`, а не принять на веру.

---

## 1. ACID без маркетинга

### 1.1 Atomicity

Транзакция применяется целиком или не применяется вовсе. В PostgreSQL это следует из MVCC: изменения записываются как новые версии строк с `xmin` текущей транзакции. Статус транзакции (in progress / committed / aborted) хранится отдельно, в **clog**. При `ROLLBACK` ничего не откатывается физически — просто транзакция помечается как aborted, и все её версии становятся невидимыми.

Отсюда: `ROLLBACK` дёшев, а «мусор» после него убирает `VACUUM`.

### 1.2 Consistency

Единственная буква, за которую отвечает **не** СУБД, а вы. База гарантирует, что не нарушатся объявленные ограничения (`NOT NULL`, `UNIQUE`, `CHECK`, `FK`). Инварианты, которые вы не выразили, никто не защитит.

Практический вывод для финтеха: «сумма проводок по счёту равна балансу» не выражается `CHECK`. Значит, это ваша ответственность — транзакция, тест и, при необходимости, регулярная сверка.

### 1.3 Isolation

Степень, в которой параллельные транзакции не видят промежуточных состояний друг друга. Уровень изоляции — компромисс между корректностью и пропускной способностью. Разбирается ниже.

### 1.4 Durability

Закоммиченное переживает сбой. Обеспечивается WAL: перед коммитом журнальная запись сбрасывается на диск (`fsync`). Настройки, которые ослабляют гарантию:

- `synchronous_commit = off` — коммит не ждёт сброса WAL; при аварии теряются последние транзакции (но целостность сохраняется). Для финансовых данных — нет.
- `fsync = off` — недопустимо нигде, кроме одноразовых тестовых окружений.

---

## 2. Границы транзакции

### 2.1 Autocommit и явный BEGIN

Без `BEGIN` каждый оператор — отдельная транзакция. Два связанных `INSERT` без транзакции могут оставить систему в состоянии «первый прошёл, второй нет».

```sql
BEGIN;
  INSERT INTO ledger_entries (...) VALUES (...);   -- дебет
  INSERT INTO ledger_entries (...) VALUES (...);   -- кредит
  UPDATE accounts SET balance_minor = ... WHERE id = ...;
COMMIT;
```

### 2.2 Правила выбора границ

1. **Транзакция должна быть короткой.** Пока она открыта, её snapshot не даёт VACUUM удалить нужные ей версии — база пухнет. Долгая транзакция также держит блокировки.
2. **Внутри транзакции не должно быть сетевых вызовов.** HTTP к внешнему сервису внутри открытой транзакции — самая дорогая ошибка: таймаут внешнего сервиса превращается в удержание соединения БД и блокировок (неделя 11).
3. **Транзакция охватывает бизнес-операцию целиком**, а не отдельные запросы. «Списать и записать проводку» — одна транзакция.
4. Не открывайте транзакцию до того, как получены все нужные данные извне.

### 2.3 Ошибка внутри транзакции

После ошибки транзакция переходит в состояние aborted: любой следующий оператор возвращает `25P02 current transaction is aborted`. Единственный выход — `ROLLBACK` (или откат к `SAVEPOINT`).

```sql
BEGIN;
  SAVEPOINT sp1;
  INSERT INTO users(email) VALUES ('dup@example.com');  -- 23505
  ROLLBACK TO SAVEPOINT sp1;                            -- транзакция снова жива
  INSERT INTO audit(...) VALUES (...);
COMMIT;
```

`SAVEPOINT` не бесплатен: каждый создаёт подтранзакцию, а их большое количество (тысячи в одной транзакции) заметно бьёт по производительности.

---

## 3. MVCC и snapshot

### 3.1 Что такое snapshot

Snapshot — это `(xmin, xmax, xip_list)`: граница «всё старше завершено», граница «всё новее ещё не началось» и список активных на момент взятия транзакций. Версия строки видима, если:

- её `xmin` закоммичен и виден в snapshot, **и**
- её `xmax` пуст, не закоммичен или не виден в snapshot.

### 3.2 Когда берётся snapshot

| Уровень | Момент взятия |
|---|---|
| Read Committed | **в начале каждого оператора** |
| Repeatable Read | при первом операторе, читающем данные |
| Serializable | так же, плюс отслеживание зависимостей |

Это одно различие объясняет почти все наблюдаемые эффекты.

### 3.3 Эксперимент, с которого начинается неделя

Сессия A:

```sql
BEGIN;                                   -- или BEGIN ISOLATION LEVEL REPEATABLE READ;
SELECT balance_minor FROM accounts WHERE id = 1;    -- 1000
```

Сессия B:

```sql
UPDATE accounts SET balance_minor = 900 WHERE id = 1;
COMMIT;
```

Сессия A:

```sql
SELECT balance_minor FROM accounts WHERE id = 1;
-- Read Committed:  900   ← non-repeatable read
-- Repeatable Read: 1000  ← стабильный snapshot
COMMIT;
```

Полезно рядом смотреть `txid_current()`, `pg_current_snapshot()` и `ctid`/`xmin` строки.

---

## 4. Уровни изоляции в PostgreSQL

### 4.1 Три реальных уровня

`READ UNCOMMITTED` в PostgreSQL существует только как синоним `READ COMMITTED`: **dirty read невозможен в принципе**, потому что незакоммиченная версия строки никому не видна.

```sql
BEGIN ISOLATION LEVEL REPEATABLE READ;
-- или
SET TRANSACTION ISOLATION LEVEL SERIALIZABLE;
-- умолчание базы:
SHOW default_transaction_isolation;
```

Уровень задаётся **в начале транзакции** и не меняется после первого запроса.

### 4.2 Read Committed (по умолчанию)

Каждый оператор видит данные, закоммиченные на момент его начала.

Особое поведение при записи: если `UPDATE`/`DELETE`/`SELECT FOR UPDATE` натыкается на строку, изменяемую другой незавершённой транзакцией, он **блокируется** до её завершения. Если та закоммитилась, PostgreSQL **перечитывает новую версию строки и заново проверяет условие `WHERE`** (EvalPlanQual). Если строка больше не подходит под условие — она пропускается.

Это ключ к пониманию, почему одно решение безопасно, а другое — нет:

```sql
-- БЕЗОПАСНО: чтение и запись атомарны внутри одного оператора
UPDATE accounts SET balance_minor = balance_minor - 100
WHERE id = 1 AND balance_minor >= 100;

-- ОПАСНО: между SELECT и UPDATE есть окно
SELECT balance_minor FROM accounts WHERE id = 1;   -- 1000
-- ... приложение считает 1000 - 100 = 900 ...
UPDATE accounts SET balance_minor = 900 WHERE id = 1;   -- lost update
```

### 4.3 Repeatable Read

Один snapshot на всю транзакцию. Все чтения согласованы между собой. В PostgreSQL это **snapshot isolation**, поэтому фантомов (в смысле повторного запроса диапазона) здесь тоже нет — реализация строже стандарта.

Что происходит при конфликте записи: если транзакция пытается изменить строку, которую после взятия её snapshot изменила и закоммитила другая транзакция, она получает

```
ERROR:  could not serialize access due to concurrent update
SQLSTATE: 40001
```

То есть lost update здесь не происходит — вместо него ошибка, которую нужно обработать retry.

Чего Repeatable Read **не** ловит: **write skew** — когда две транзакции изменяют разные строки, но вместе нарушают инвариант.

### 4.4 Serializable (SSI)

Serializable Snapshot Isolation отслеживает зависимости чтения/записи между конкурентными транзакциями и, обнаружив цикл, способный привести к нарушению сериализуемости, откатывает одну из них:

```
ERROR:  could not serialize access due to read/write dependencies among transactions
HINT:   The transaction might succeed if retried.
SQLSTATE: 40001
```

Свойства:

- Гарантирует, что результат эквивалентен какому-то последовательному выполнению. Это самая сильная гарантия, и она снимает write skew.
- Не использует дополнительных блокировок для чтения — использует **предикатные блокировки** (SIREAD), которые не блокируют, а только отслеживают.
- Цена: часть транзакций откатывается, и **приложение обязано уметь retry**. Без retry Serializable просто превращает аномалии в ошибки для пользователя.
- Предикатные блокировки могут «огрубляться» до страницы или отношения при нехватке памяти → больше ложных откатов.
- Для корректности **все** участвующие транзакции должны быть Serializable.

### 4.5 Сводная таблица

| Аномалия | Read Committed | Repeatable Read | Serializable |
|---|---|---|---|
| Dirty read | невозможен | невозможен | невозможен |
| Non-repeatable read | **возможен** | нет | нет |
| Phantom read | **возможен** | нет (SI) | нет |
| Lost update | **возможен** | ошибка 40001 | ошибка 40001 |
| Write skew | **возможен** | **возможен** | ошибка 40001 |
| Read-only anomaly | возможна | возможна | нет |

---

## 5. Аномалии по timeline

Каждую нужно воспроизвести в `session-a.sql` / `session-b.sql` и записать timeline.

### 5.1 Non-repeatable read

```
A: BEGIN; SELECT balance FROM accounts WHERE id=1;   -- 1000
B: UPDATE accounts SET balance=900 WHERE id=1; COMMIT;
A: SELECT balance FROM accounts WHERE id=1;          -- RC: 900, RR: 1000
```

### 5.2 Phantom read

```
A: BEGIN; SELECT count(*) FROM payments WHERE status='NEW';   -- 5
B: INSERT INTO payments(status,...) VALUES('NEW',...); COMMIT;
A: SELECT count(*) FROM payments WHERE status='NEW';          -- RC: 6, RR: 5
```

### 5.3 Lost update

```
A: BEGIN; SELECT balance FROM accounts WHERE id=1;  -- 1000
B: BEGIN; SELECT balance FROM accounts WHERE id=1;  -- 1000
A: UPDATE accounts SET balance=900 WHERE id=1; COMMIT;   -- списал 100
B: UPDATE accounts SET balance=900 WHERE id=1; COMMIT;   -- списал 100
-- Итог: списано 200, баланс 900. Сто рублей исчезли из учёта.
```

На Read Committed проходит молча. На Repeatable Read транзакция B получит `40001`.

### 5.4 Write skew

Инвариант: суммарный баланс двух счетов одного пользователя не должен уходить в минус.

```
A: BEGIN ISOLATION LEVEL REPEATABLE READ;
   SELECT sum(balance) FROM accounts WHERE user_id=1;   -- 100
B: BEGIN ISOLATION LEVEL REPEATABLE READ;
   SELECT sum(balance) FROM accounts WHERE user_id=1;   -- 100
A: UPDATE accounts SET balance=balance-100 WHERE id=1; COMMIT;
B: UPDATE accounts SET balance=balance-100 WHERE id=2; COMMIT;
-- Обе проверки были верны, обе строки разные → 40001 не будет.
-- Инвариант нарушен: суммарно -100.
```

Лечится Serializable, явной блокировкой обеих строк или изменением модели (один счёт-агрегат / ledger).

### 5.5 Read-only anomaly

Существует сценарий, где даже транзакция, ничего не пишущая, видит несогласованное состояние при snapshot isolation. Это одна из причин, по которой Serializable в PostgreSQL требует, чтобы **все** транзакции были Serializable, и почему существует `SET TRANSACTION READ ONLY DEFERRABLE`.

---

## 6. Serialization failure и retry

### 6.1 Что можно повторять

| SQLSTATE | Смысл | Retry |
|---|---|---|
| 40001 | serialization_failure | **да** |
| 40P01 | deadlock_detected | **да** (неделя 7) |
| 55P03 | lock_not_available | да, ограниченно |
| 57014 | query_canceled (statement_timeout) | зависит от контекста |
| 23505 | unique_violation | **нет** (кроме идемпотентности) |
| 23503 / 23514 | FK / CHECK violation | **нет** |

### 6.2 Как правильно

Retry повторяет **всю бизнес-транзакцию с начала**: новое соединение/новая транзакция, повторное чтение всех данных, повторное принятие решения. Причина: после отката всё, что транзакция прочитала, недействительно. Повторить только последний `UPDATE` — значит применить решение, принятое по устаревшим данным.

Правила:

- ограниченное число попыток (3-5), затем честная ошибка клиенту;
- экспоненциальный backoff + **jitter**, иначе повторы синхронизируются и конфликтуют снова;
- повторяемая операция должна быть **идемпотентной** — иначе retry создаст дубль (неделя 7);
- каждый retry логируется с `requestId` и причиной; всплеск `40001` — метрика для алертов (неделя 12).

### 6.3 Псевдокод

```
for attempt in 1..maxAttempts:
    try:
        BEGIN ISOLATION LEVEL SERIALIZABLE
        ... вся бизнес-логика ...
        COMMIT
        return
    catch SQLSTATE in (40001, 40P01):
        ROLLBACK
        sleep(base * 2^attempt + random_jitter)
throw TooManyRetries
```

---

## 7. Безопасное списание: четыре способа

Задание недели — реализовать минимум два и сравнить.

### 7.1 Атомарный UPDATE с условием

```sql
UPDATE accounts
SET balance_minor = balance_minor - :amount
WHERE id = :id AND balance_minor >= :amount;
-- проверить количество затронутых строк: 0 → недостаточно средств
```

Плюсы: один оператор, максимальный throughput, корректно даже на Read Committed. Минусы: не подходит, если решение требует сложных вычислений или чтения нескольких таблиц.

### 7.2 Пессимистичная блокировка

```sql
BEGIN;
SELECT balance_minor FROM accounts WHERE id = :id FOR UPDATE;
-- вычисления
UPDATE accounts SET balance_minor = :new WHERE id = :id;
COMMIT;
```

Плюсы: интуитивно, работает для любой логики. Минусы: сериализует доступ к строке, возможны lock wait и deadlock при нескольких строках (неделя 7). Обязателен `lock_timeout`.

### 7.3 Оптимистичная блокировка

```sql
UPDATE accounts
SET balance_minor = :new, version = version + 1
WHERE id = :id AND version = :expectedVersion;
-- 0 строк → конфликт → перечитать и повторить (или 409 клиенту)
```

Плюсы: нет блокировок, хорошо при низкой конкуренции, естественно ложится на HTTP (`If-Match`/`409`). Минусы: при высокой конкуренции много холостых попыток.

### 7.4 Serializable + retry

Логика пишется «наивно», корректность обеспечивает СУБД, приложение отвечает за retry. Плюсы: защищает и от write skew. Минусы: откаты, обязательный retry, деградация при конкуренции за одну строку.

### 7.5 И в любом случае

```sql
ALTER TABLE accounts ADD CONSTRAINT accounts_balance_non_negative CHECK (balance_minor >= 0);
```

Constraint не заменяет ни один из способов — он последний рубеж, который поймает ошибку в логике.

### 7.6 Hot row

Когда все операции бьют в одну строку (баланс мерчанта, общий счётчик), любой из подходов упирается в сериализацию. Архитектурные ответы: ledger вместо mutable balance (запись только `INSERT`, баланс считается или обновляется агрегатом), шардирование счётчика на N строк с последующим суммированием, батчинг. Это тема недель 7 и 16.

---

## 8. Наблюдение

```sql
-- активные транзакции и что они делают
SELECT pid, state, xact_start, now() - xact_start AS xact_age, wait_event_type, wait_event, left(query, 80)
FROM pg_stat_activity
WHERE backend_type = 'client backend' AND state <> 'idle'
ORDER BY xact_start;

-- самые долгие транзакции (враги VACUUM)
SELECT pid, now() - xact_start AS age, query FROM pg_stat_activity
WHERE xact_start IS NOT NULL ORDER BY age DESC LIMIT 5;

-- 'idle in transaction' — почти всегда баг приложения
SELECT pid, now() - state_change AS idle_for, query
FROM pg_stat_activity WHERE state = 'idle in transaction';
```

Защитные настройки: `idle_in_transaction_session_timeout`, `statement_timeout`, `lock_timeout`. В финтех-сервисе они должны быть выставлены явно, а не оставлены безлимитными.

---

## 9. Типичные ошибки недели

1. Read-modify-write в приложении вместо атомарного `UPDATE`.
2. Serializable без retry — аномалии заменяются ошибками для пользователя.
3. Retry только последнего оператора вместо всей транзакции.
4. Retry без ограничения попыток и без jitter.
5. Retry неидемпотентной операции → дубли.
6. Внешний HTTP-вызов внутри открытой транзакции.
7. Мнение, что «Serializable медленный, поэтому не нужен», без замера на своей нагрузке.
8. Уверенность, что уровень изоляции спасёт от плохой модели данных.
9. Долгая аналитическая транзакция, из-за которой не работает VACUUM.

---

## 10. Критерий готовности

- Предсказываете видимость строк **до** запуска эксперимента и потом проверяете.
- Воспроизвели non-repeatable read, phantom, lost update и write skew в двух сессиях, с сохранённым timeline.
- Получили `40001` и реализовали ограниченный retry всей транзакции.
- Реализовали безопасное списание минимум двумя способами и можете сравнить их по корректности, throughput и сложности.
- Знаете, какие ошибки повторять можно, а какие нельзя.

## 11. Официальные материалы

- PostgreSQL: Chapter 13 — Concurrency Control (13.2 Transaction Isolation, 13.4 Data Consistency Checks at the Application Level).
- PostgreSQL: Chapter 30 — Reliability and the Write-Ahead Log.
- PostgreSQL: `SET TRANSACTION`, `SAVEPOINT`.
- PostgreSQL: Appendix A — Error Codes (класс 40).
