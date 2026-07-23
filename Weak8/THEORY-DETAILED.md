# Неделя 8 — подробная теория

Spring Data, JDBC/JPA, миграции и границы транзакций.

> Цель недели — не «подключить ORM», а сохранить всё понимание, добытое на неделях 3-7, после того как между вами и PostgreSQL появился слой абстракции. Если после этой недели вы не можете сказать, какой SQL выполнится и с каким планом, неделя не пройдена.

---

## 1. JDBC, пул соединений и транзакция

### 1.1 Как соединение связывается с транзакцией

Spring хранит соединение текущей транзакции в `TransactionSynchronizationManager` — по сути ThreadLocal. Отсюда:

- все репозитории, вызванные внутри `@Transactional`-метода, получают **одно и то же** соединение и работают в одной транзакции;
- переход в другой поток теряет контекст транзакции — это будет важно на неделе 11 с корутинами;
- соединение занято **всё время транзакции**, а не время выполнения запроса.

### 1.2 Размер пула

Каждое соединение к PostgreSQL — отдельный процесс на сервере. Пул из 100 соединений при 8 ядрах не ускоряет, а замедляет: растут переключения контекста и конкуренция за буферы.

Практическое правило: начинать с ~10 и увеличивать по метрикам. Полезные параметры HikariCP: `maximum-pool-size`, `connection-timeout` (быстрый отказ вместо бесконечного ожидания), `max-lifetime` (меньше таймаута на стороне БД/прокси), `leak-detection-threshold` (лог о невозвращённом соединении).

Связка с неделей 1: 200 worker-потоков Tomcat и 10 соединений означают, что при насыщении 190 запросов ждут на `getConnection()`. Это нормально, если ожидание ограничено и видно в метриках (неделя 12).

### 1.3 Классическая ловушка `REQUIRES_NEW`

`Propagation.REQUIRES_NEW` приостанавливает текущую транзакцию и открывает новую — **на втором соединении**. Если каждый из 10 параллельных запросов, уже держащих соединение, попросит второе из пула на 10, все они будут ждать друг друга. Это самоблокировка приложения, не базы.

---

## 2. Spring Data JPA: жизненный цикл и dirty checking

### 2.1 Состояния entity

```
      new(transient)
            │ persist()/save()
            ▼
        managed  ◄────merge()──── detached
            │                        ▲
            │ flush/commit           │ detach(), close(), выход из транзакции
            ▼                        │
      строка в БД ───────────────────┘
```

**Persistence context** (первого уровня кеш) живёт в пределах транзакции и хранит снимок каждой загруженной сущности.

### 2.2 Dirty checking

```kotlin
@Transactional
fun freeze(accountId: Long) {
    val account = accountRepository.findById(accountId).orElseThrow()
    account.status = "FROZEN"
    // save() не нужен: при коммите Hibernate сравнит со снимком и выполнит UPDATE
}
```

Это удобно и опасно одновременно. Опасность: любое изменение managed-сущности попадает в базу, даже сделанное «для расчёта». Правила:

- не мутируйте entity вне явного намерения записать;
- для чтения используйте `readOnly = true` (Hibernate пропускает dirty checking) или **проекции**, которые вообще не являются entity.

### 2.3 Когда происходит flush

1. При коммите транзакции.
2. Перед выполнением JPQL/native-запроса, который может затронуть изменённые сущности (`FlushModeType.AUTO`).
3. По явному `entityManager.flush()`.

Понимание порядка важно: `UPDATE` может выполниться не там, где вы написали присваивание, а позже — что меняет порядок захвата блокировок и, следовательно, вероятность дедлока (неделя 7).

### 2.4 Kotlin-специфика entity

- Entity должен иметь конструктор без аргументов → плагин `kotlin-jpa` (`no-arg`), включённый стартером.
- Классы `final` → плагин `all-open` для `@Entity`.
- **`data class` для entity — плохая идея**: сгенерированные `equals`/`hashCode` по всем полям ломают identity (id появляется только после вставки, поля меняются). Пишите обычный класс с `equals`/`hashCode` по бизнес-ключу или по id с осторожностью.
- Nullable-типы Kotlin должны соответствовать `NOT NULL` в схеме — иначе `null` из базы попадёт в non-null поле и упадёт неочевидно.

---

## 3. N+1, lazy loading, fetch join, projections

### 3.1 Как выглядит N+1

```kotlin
val accounts = accountRepository.findByUserId(userId)   // 1 запрос
accounts.forEach { println(it.payments.size) }          // N запросов
```

При 100 счетах это 101 обращение к базе, каждое с сетевым round-trip. Ни один индекс это не исправит.

### 3.2 Способы лечения

**JOIN FETCH / `@EntityGraph`**

```kotlin
@Query("select distinct a from Account a join fetch a.payments where a.userId = :userId")
fun findWithPayments(userId: Long): List<Account>

@EntityGraph(attributePaths = ["payments"])
fun findByUserId(userId: Long): List<Account>
```

Ограничение: `JOIN FETCH` коллекции **несовместим с пагинацией** — Hibernate выдаст предупреждение `HHH90003004` и выполнит пагинацию **в памяти**, загрузив всё. Это худший из возможных исходов, и его надо уметь распознавать.

**Batch fetching**

```yaml
spring.jpa.properties.hibernate.default_batch_fetch_size: 50
```

Превращает N запросов в N/50: Hibernate догружает коллекции пачками через `IN (...)`. Дешёвый способ существенно улучшить ситуацию без переписывания запросов.

**Projections**

```kotlin
interface PaymentSummary {
    val id: Long
    val amountMinor: Long
    val status: String
}

@Query("select p.id as id, p.amountMinor as amountMinor, p.status as status from Payment p where p.accountId = :acc")
fun summaries(acc: Long): List<PaymentSummary>
```

Проекция не является entity: нет persistence context, нет dirty checking, читаются только нужные колонки. Для read-heavy endpoint'ов это правильный выбор — он же открывает дорогу к Index Only Scan (неделя 5).

**Явный SQL**

Для отчётов, оконных функций, keyset-пагинации и всего, где важен план, — `JdbcTemplate`.

### 3.3 open-in-view

По умолчанию Spring Boot включает `spring.jpa.open-in-view=true`: EntityManager (и соединение!) живёт до конца обработки HTTP-запроса, включая сериализацию ответа. Последствия:

- lazy-поля незаметно догружаются во время сериализации JSON — N+1 прячется;
- соединение занято дольше, чем нужно.

**Выключайте явно:**

```yaml
spring.jpa.open-in-view: false
```

После этого `LazyInitializationException` начнёт честно указывать на места, где fetch-план не продуман.

### 3.4 Диагностика

| Инструмент | Что даёт |
|---|---|
| `spring.jpa.show-sql` | грубый вывод SQL без параметров и без счётчика |
| `logging.level.org.hibernate.SQL=DEBUG` + `BasicBinder=TRACE` | SQL с параметрами |
| datasource-proxy / p6spy | реальный SQL, время, счётчик запросов на запрос |
| `hibernate.generate_statistics` | сводка по сессии |
| `pg_stat_statements` | истина со стороны базы |

Хороший приём: тест, который считает число SQL-запросов на один HTTP-вызов и падает при росте.

---

## 4. Блокировки в JPA

### 4.1 Оптимистичная

```kotlin
@Entity
class Account {
    @Version
    var version: Long = 0
}
```

Hibernate добавляет `AND version = ?` в `UPDATE` и инкрементирует поле. Несовпадение → `OptimisticLockException` / `ObjectOptimisticLockingFailureException` → в API `409`. Это ровно тот механизм, который заложен на неделе 2 и реализован руками на неделе 6.

### 4.2 Пессимистичная

```kotlin
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("select a from Account a where a.id = :id")
fun findForUpdate(id: Long): Account?
```

Генерирует `SELECT ... FOR UPDATE`. Обязательно задавайте таймаут:

```kotlin
@QueryHints(QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000"))
```

`PESSIMISTIC_READ` → `FOR SHARE`. Порядок захвата строк (неделя 7) остаётся вашей ответственностью — сортируйте id перед блокировкой.

---

## 5. `@Transactional`: границы и подводные камни

### 5.1 Прокси

Транзакция открывается, только когда вызов проходит через прокси:

```kotlin
@Service
class PaymentService {
    fun process(id: Long) = doProcess(id)     // транзакции НЕ будет

    @Transactional
    fun doProcess(id: Long) { ... }
}
```

Также не перехватываются `private` и `final` методы (в Kotlin спасает плагин `all-open` для `@Service`/`@Transactional`). Решения: вынести в отдельный бин или использовать `TransactionTemplate`.

### 5.2 Атрибуты

- `readOnly = true` — на всех читающих методах: `SET TRANSACTION READ ONLY` плюс отключение dirty checking.
- `isolation` — `REPEATABLE_READ`/`SERIALIZABLE` там, где это обосновано неделей 6.
- `timeout` — верхняя граница; дополняйте `statement_timeout` и `lock_timeout` на стороне БД.
- `propagation = REQUIRES_NEW` — только осознанно, помня про второе соединение.

### 5.3 Правила отката

Откат по умолчанию — на unchecked-исключениях (в Kotlin это все). Но **пойманное и не проброшенное исключение отменяет откат**. И помните: после ошибки PostgreSQL транзакция aborted, продолжать в ней нельзя без `SAVEPOINT` (`Propagation.NESTED`).

### 5.4 Где транзакция

```
Controller           — без транзакции
  Service            — @Transactional: одна бизнес-операция
    Repository       — участвует, своей не открывает
```

Внутри транзакции запрещены сетевые вызовы, `sleep`, длинные вычисления и неоткатываемые побочные эффекты (неделя 11).

### 5.5 Retry

Retry — уровнем **выше** транзакции: транзакция должна полностью откатиться, после чего операция повторяется с чтением свежих данных. Повторяем `40001` (`CannotSerializeTransactionException`) и `40P01` (`DeadlockLoserDataAccessException`); не повторяем `DuplicateKeyException` и прочие нарушения ограничений. Обязательна идемпотентность (неделя 7).

---

## 6. Когда JPA, когда JdbcTemplate

| Задача | Инструмент | Почему |
|---|---|---|
| CRUD агрегата | JPA | dirty checking, каскады, меньше кода |
| Список с фильтрами и keyset-пагинацией | `JdbcTemplate` | нужен точный SQL под индекс |
| Отчёт с оконными функциями | `JdbcTemplate` | JPQL этого не выражает |
| Массовая вставка | `JdbcTemplate.batchUpdate` / `COPY` | JPA генерирует по запросу на строку |
| Перевод с `FOR UPDATE` и ledger | `JdbcTemplate` (или JPA с явными lock hints) | важен точный порядок и набор операторов |
| `INSERT ... ON CONFLICT` | `JdbcTemplate` | JPA этого не умеет |

Смешивать можно и нужно — в одной транзакции они работают на одном соединении. Единственное требование: понимать, что происходит.

**Задание недели:** реализовать один сложный запрос двумя способами и сравнить сгенерированный SQL, число запросов, план и объём кода.

---

## 7. Flyway и миграции

### 7.1 Механика

- Файлы `V<версия>__<описание>.sql` (например, `V3__add_idempotency_keys.sql`), применяются по возрастанию версии.
- История в таблице `flyway_schema_history` вместе с **checksum**.
- Изменение уже применённого файла → ошибка валидации. **Применённую миграцию не редактируют никогда** — пишут новую.
- Repeatable-миграции (`R__`) — для представлений и функций, применяются при изменении checksum.
- Baseline — для подключения Flyway к существующей базе.

### 7.2 Backward-compatible миграции (expand-contract)

Во время деплоя одновременно работают старая и новая версии приложения. Значит, схема после миграции обязана быть совместима с **обеими**.

Схема на три релиза (добавить обязательную колонку):

1. **Expand.** `ALTER TABLE ... ADD COLUMN new_col text` (nullable, без `NOT NULL`). Старый код её не замечает.
2. **Migrate.** Новый код пишет и в старую, и в новую колонку; фоновая задача заполняет старые строки **пачками** (не одним `UPDATE` на миллион строк — это долгая транзакция и лавина WAL).
3. **Contract.** Когда все строки заполнены и старый код выведен: `SET NOT NULL` (через `CHECK ... NOT VALID` + `VALIDATE CONSTRAINT`, чтобы не сканировать таблицу под сильной блокировкой), удаление старой колонки.

Переименование колонки — то же самое: добавить новую, дублировать запись, перелить, удалить старую. Прямой `RENAME` ломает работающие инстансы.

### 7.3 Опасные операции

| Операция | Риск | Как безопасно |
|---|---|---|
| `ALTER TABLE ... SET NOT NULL` | полное сканирование под `ACCESS EXCLUSIVE` | `ADD CONSTRAINT ... CHECK (col IS NOT NULL) NOT VALID` → `VALIDATE CONSTRAINT` → затем `SET NOT NULL` |
| `ADD COLUMN ... NOT NULL DEFAULT` | в старых версиях — перезапись таблицы | в PG 11+ дёшево, но проверьте версию |
| `CREATE INDEX` | `SHARE`, блокирует запись | `CREATE INDEX CONCURRENTLY` в отдельной миграции без транзакции |
| `ADD FOREIGN KEY` | сканирование обеих таблиц | `NOT VALID` → `VALIDATE CONSTRAINT` |
| Смена типа колонки | перезапись таблицы | новая колонка + перелив |
| `UPDATE` всей таблицы | долгая транзакция, bloat, WAL | батчами по 1000-10000 строк с коммитами |

**Очередь блокировок.** `ALTER TABLE`, ждущий долгую транзакцию, блокирует все последующие запросы к таблице, включая `SELECT`. Поэтому в миграциях всегда:

```sql
SET lock_timeout = '3s';
```

и повтор при неудаче — лучше не применить миграцию, чем остановить сервис.

### 7.4 Flyway и CONCURRENTLY

`CREATE INDEX CONCURRENTLY` не выполняется внутри транзакции. Во Flyway такая миграция помечается специально (в конфигурации — исключение из транзакционного выполнения; в имени — по конвенции проекта). При сбое остаётся невалидный индекс: проверить `pg_index.indisvalid`, удалить и повторить.

### 7.5 Миграции в CI

Обязательный этап пайплайна (неделя 13): применить все миграции на **пустой** базе с нуля и убедиться, что они проходят. Второй полезный этап — применить их поверх дампа схемы предыдущего релиза.

---

## 8. Лаборатория недели

1. Подключить PostgreSQL, HikariCP и Flyway; перенести схему недели 3 в миграции.
2. Реализовать перевод (неделя 7) с явной сервисной транзакцией; показать сгенерированный SQL.
3. Реализовать один сложный запрос дважды — JPA и `JdbcTemplate` — и сравнить SQL, число запросов, план и код.
4. Воспроизвести N+1 по логам, посчитать запросы, исправить тремя способами (`JOIN FETCH`, `batch_fetch_size`, проекция) и сравнить.
5. Выключить `open-in-view` и починить всё, что после этого сломается.
6. Показать self-invocation: данные сохранились частично, отката не было.
7. Написать миграцию добавления `NOT NULL` колонки на большой таблице по схеме expand-contract, замерив блокировки.
8. Прогнать миграции на чистой базе в CI.

---

## 9. Типичные ошибки недели

1. `open-in-view = true` по умолчанию и «незаметный» N+1.
2. `JOIN FETCH` коллекции вместе с пагинацией.
3. Entity как `data class`.
4. Entity, возвращаемый напрямую из контроллера.
5. Self-invocation `@Transactional`.
6. `REQUIRES_NEW` в цикле → исчерпание пула.
7. Правка уже применённой миграции.
8. `SET NOT NULL` на большой таблице в рабочее время.
9. `CREATE INDEX` без `CONCURRENTLY` в миграции.
10. Разовый `UPDATE` на миллион строк в миграции.
11. Уверенность, что «ORM сам сделает правильно», без единого просмотренного плана.

---

## 10. Критерий готовности

- Ни один критичный запрос не является неожиданностью: вы видели его SQL и его план.
- Объясняете, где заканчивается Spring и начинается PostgreSQL.
- Показываете границы транзакций в коде и обосновываете каждую.
- Умеете написать безопасную миграцию и объяснить, какие блокировки она берёт.
- N+1 обнаруживается тестом, а не в проде.

## 11. Официальные материалы

- Spring Data JPA Reference — Repositories, Query Methods, Projections, Locking.
- Spring Framework — Transaction Management (declarative, propagation, proxy mode).
- Hibernate ORM User Guide — Persistence Context, Fetching, Batch fetching.
- Flyway Documentation — Migrations, Callbacks, Baseline.
- PostgreSQL: `ALTER TABLE` (locking), `CREATE INDEX CONCURRENTLY`, Chapter 13.3 Explicit Locking.
