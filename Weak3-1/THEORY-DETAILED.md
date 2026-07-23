# Неделя 3-1 — подробная теория (Spring + JDBC поверх PostgreSQL)

Та же тема, что и в [Weak3](../Weak3/THEORY-DETAILED.md), но с приложением сверху: как HTTP-запрос доходит до страницы на диске, и где проходят границы ответственности.

---

## 1. Краткий пересказ физической части

Чтобы файл читался отдельно, повторим минимум (подробности — в SQL-first версии):

- Таблица — **heap**: последовательность страниц по 8 КБ. В странице лежат tuples — **версии** строк.
- `ctid = (страница, item)` — адрес версии; `xmin`/`xmax` — транзакции, создавшая и погасившая версию.
- **UPDATE создаёт новую версию** и обновляет все индексы (кроме HOT-update); старая версия остаётся мёртвой до `VACUUM`.
- **MVCC**: транзакция работает со snapshot; читатели и писатели не блокируют друг друга.
- **WAL** даёт durability, **checkpoint** ограничивает время восстановления, **VACUUM** убирает мёртвые версии и ведёт visibility map, **ANALYZE** собирает статистику для планировщика.

---

## 2. Полный путь запроса

```
curl → Tomcat worker thread
     → @RestController (неделя 1)
     → @Valid DTO → сервис
     → JdbcTemplate.update(...)
     → DataSource.getConnection() — соединение из HikariCP
     → JDBC-драйвер: extended query protocol (Parse/Bind/Execute)
     → backend-процесс PostgreSQL
         parser → rewriter → planner (по статистике!) → executor
     → shared_buffers → страницы heap/индекса → WAL при записи
     → ResultSet → RowMapper → domain → DTO → JSON
```

Каждый шаг может стать узким местом, и на разных неделях мы будем смотреть на разные: пул потоков (1), пул соединений (3-1), план (5), блокировки (7), транзакции (8), метрики (12).

### 2.1 Пул соединений

HikariCP держит открытые соединения, потому что установка соединения к PostgreSQL дорога: это форк отдельного **процесса** на стороне сервера, ~5-10 МБ памяти.

Ключевые параметры:

| Параметр | Смысл | Практика |
|---|---|---|
| `maximum-pool-size` | верхняя граница одновременных соединений | начинать с 10; «больше» почти всегда хуже |
| `connection-timeout` | сколько ждать свободное соединение | 2-5 c, чтобы отказ был быстрым |
| `max-lifetime` | принудительное пересоздание | меньше, чем таймаут на стороне БД/прокси |
| `leak-detection-threshold` | лог о невозвращённом соединении | включить в dev |

Контринтуитивное правило: **пул должен быть меньше, чем хочется**. PostgreSQL не масштабируется линейно по числу процессов; при перегрузе растут переключения контекста и борьба за буферы. Ориентир — порядка `2 × ядра` плюс запас на дисковые ожидания. Ограниченный пул превращает перегрузку в честную очередь и быстрый отказ вместо деградации всей базы.

Связь с неделей 1: worker-потоков Tomcat 200, соединений 10. Значит, при полном насыщении 190 потоков ждут на `getConnection()`. Это нормально — важно, чтобы ожидание было ограничено таймаутом и попадало в метрики (неделя 12).

### 2.2 Autocommit

Без явной транзакции JDBC работает в autocommit: каждый оператор — отдельная транзакция. Практическое следствие уже на этой неделе: два `INSERT` (payment + ledger_entry), выполненные подряд без транзакции, могут остаться в состоянии «первый прошёл, второй нет». Явные границы транзакции — тема недели 8, но проблема видна здесь.

---

## 3. Три уровня защиты инварианта

### 3.1 Bean Validation на DTO

```kotlin
data class CreatePaymentRequest(
    @field:Positive val accountId: Long,
    @field:Positive val amountMinor: Long,
    @field:Pattern(regexp = "[A-Z]{3}") val currency: String,
)
```

Проверяет **форму** запроса до бизнес-логики. Ничего не знает о состоянии базы и о конкурентности.

### 3.2 Домен и сервис

```kotlin
fun createPayment(cmd: CreatePayment): Payment {
    val account = accounts.findById(cmd.accountId) ?: throw AccountNotFound(cmd.accountId)
    require(account.status == ACTIVE) { "account is not active" }
    require(account.currency == cmd.currency) { "currency mismatch" }
    ...
}
```

Проверяет правила, зависящие от состояния, и даёт понятные сообщения. Слабое место: между чтением `account` и записью проходит время (TOCTOU). При конкурентных запросах проверка может быть верной в момент чтения и ложной в момент записи.

### 3.3 Constraint базы

```sql
CONSTRAINT accounts_balance_non_negative CHECK (balance_minor >= 0)
CONSTRAINT payments_amount_positive      CHECK (amount_minor > 0)
CONSTRAINT idempotency_keys_pkey         PRIMARY KEY (key)
```

Единственный уровень, который держится при любой конкуренции, при нескольких инстансах приложения и при ручном SQL. Цена — сообщение об ошибке непригодно для пользователя.

**Рабочая схема:** проверять в домене ради UX, полагаться на constraint ради корректности, а нарушение constraint аккуратно переводить в понятный ответ.

---

## 4. Трансляция ошибок БД в HTTP

Spring переводит `SQLException` в иерархию `DataAccessException` через `SQLExceptionTranslator`, ориентируясь на SQLSTATE:

| SQLSTATE | Ситуация | Spring-исключение | Ответ API |
|---|---|---|---|
| 23505 | unique_violation | `DuplicateKeyException` | `409` (или `200` с существующим результатом для идемпотентности — неделя 7) |
| 23503 | foreign_key_violation | `DataIntegrityViolationException` | `409` / `422` |
| 23514 | check_violation | `DataIntegrityViolationException` | `422` |
| 23502 | not_null_violation | `DataIntegrityViolationException` | `400` (ошибка в коде) |
| 40001 | serialization_failure | `CannotSerializeTransactionException` | retry (неделя 6) |
| 40P01 | deadlock_detected | `DeadlockLoserDataAccessException` | retry (неделя 7) |
| 55P03 | lock_not_available | `CannotAcquireLockException` | `409` / retry |

Различать конкретный constraint по имени можно, но аккуратно:

```kotlin
catch (e: DuplicateKeyException) {
    val cause = e.cause as? PSQLException
    when (cause?.serverErrorMessage?.constraint) {
        "idempotency_keys_pkey" -> return existingResult(...)
        "users_email_key"       -> throw EmailAlreadyUsed()
        else                    -> throw e
    }
}
```

Правило: **имена constraint задаются явно** (`CONSTRAINT users_email_key UNIQUE (email)`), иначе вы завязываетесь на автогенерируемое имя.

Что **нельзя** возвращать клиенту: `e.message` целиком. В нём — имя таблицы, имя constraint и иногда значения полей.

---

## 5. JDBC-слой на практике

### 5.1 Явный SQL

```kotlin
@Repository
class PaymentJdbcRepository(private val jdbc: NamedParameterJdbcTemplate) {

    fun insert(cmd: CreatePayment): Long = jdbc.queryForObject(
        """
        INSERT INTO payments (account_id, amount_minor, currency, status, created_at)
        VALUES (:accountId, :amountMinor, :currency, 'NEW', now())
        RETURNING id
        """,
        mapOf(
            "accountId" to cmd.accountId,
            "amountMinor" to cmd.amountMinor,
            "currency" to cmd.currency,
        ),
        Long::class.java,
    )!!

    fun findById(id: Long): Payment? = jdbc.query(
        "SELECT id, account_id, amount_minor, currency, status, created_at FROM payments WHERE id = :id",
        mapOf("id" to id),
        PaymentRowMapper,
    ).firstOrNull()
}
```

Заметки:

- `RETURNING id` избавляет от второго запроса за ключом.
- Перечисляйте колонки явно: `SELECT *` ломается при изменении схемы и тянет лишнее.
- `queryForObject` бросает `EmptyResultDataAccessException` при отсутствии строки — для «может не быть» используйте `query(...).firstOrNull()`.
- `RowMapper` — точка, где физические типы превращаются в доменные (`timestamptz` → `Instant`, `bigint` → `Money`).

### 5.2 SQL injection

Единственно допустимая форма — параметризация. Драйвер отправляет текст запроса и значения раздельно (extended query protocol), значение никогда не становится частью SQL.

```kotlin
// НИКОГДА
jdbc.queryForList("SELECT * FROM users WHERE email = '$email'")
```

Динамическими могут быть только имена/направление сортировки, и они собираются из **белого списка**, а не из строки клиента.

### 5.3 Batch и массовая загрузка

Для генерации 100 000 платежей из приложения используйте `batchUpdate` — иначе каждый INSERT это отдельный round-trip. Ещё быстрее — `COPY` через `CopyManager` драйвера. Полезное наблюдение для недели 4: время загрузки с индексами и без отличается в разы.

---

## 6. Что делает лаборатория

1. **`POST /api/users`, `POST /api/accounts`, `POST /api/payments`** — прогнать невалидный запрос дважды: сначала он падает на Bean Validation (`400`), потом — если убрать аннотацию — на `CHECK` (`409`/`422`). Это делает границу уровней наглядной.
2. **`GET /api/accounts/{id}/physical-tuple`** — прочитать `ctid` и `xmin`, выполнить UPDATE баланса, прочитать снова. Затем `VACUUM (VERBOSE, ANALYZE)` и посмотреть, что изменилось.
3. **Дописать `sql/queries.sql` до 20 запросов** — JOIN, GROUP BY/HAVING, CTE, window function (running balance по ledger).
4. **Сгенерировать 100 000 платежей** и сравнить:

   ```sql
   SELECT pg_size_pretty(pg_relation_size('payments')),
          pg_size_pretty(pg_indexes_size('payments'));
   SELECT reltuples::bigint FROM pg_class WHERE relname='payments';  -- до ANALYZE
   ANALYZE payments;
   SELECT reltuples::bigint FROM pg_class WHERE relname='payments';  -- после
   ```

5. **Найти инварианты, продублированные на двух уровнях**, и для каждого написать, почему дубль оправдан.

---

## 7. Типичные ошибки недели

1. Проверка уникальности через `SELECT ... IF NOT EXISTS THEN INSERT` без UNIQUE-индекса — под конкуренцией создаёт дубли.
2. `e.message` из `DataIntegrityViolationException` в теле ответа.
3. `SELECT *` + `RowMapper` по индексам колонок — ломается при добавлении поля.
4. Пул соединений на 100 «чтобы точно хватило».
5. Два связанных INSERT без транзакции.
6. Хранение `ctid` или расчёт на стабильный физический порядок строк.
7. Замер производительности на пустой таблице.

---

## 8. Критерий готовности

- Проходите путь от endpoint до конкретного SQL и обратно, называя каждый слой.
- Для каждого инварианта говорите, на каком уровне он защищён и почему именно там.
- Показываете `ctid`/`xmin` до и после UPDATE и объясняете изменение через MVCC.
- Каждое исключение БД отображается в осмысленный код ответа, детали не утекают.
- Написали не меньше 20 SQL-запросов руками, до всякого ORM.

## 9. Официальные материалы

- PostgreSQL: Chapter 13 — Concurrency Control; Chapter 73 — Physical Storage.
- PostgreSQL: Appendix A — Error Codes (SQLSTATE).
- Spring Framework — Data Access: JDBC, Exception Translation.
- HikariCP — About Pool Sizing.
- PostgreSQL JDBC Driver — Using the Driver, `CopyManager`.
