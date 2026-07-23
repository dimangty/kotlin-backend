# Неделя 10 — подробная теория

Тестирование базы и конкурентности.

> Правило недели: **тест, который не падает при удалении защиты, ничего не проверяет.** Удалите `UNIQUE`, уберите `FOR UPDATE`, снимите `CHECK` — тесты обязаны покраснеть. Если они зелёные, у вас ложная уверенность.

---

## 1. Уровни тестов

### 1.1 Пирамида для backend с базой

| Уровень | Что | Инструменты | Когда |
|---|---|---|---|
| **Unit** | чистые функции, доменные правила, расчёты | JUnit 5, без Spring | всегда, много |
| **Slice** | один слой изолированно | `@WebMvcTest`, `@DataJpaTest`, `@JsonTest` | контракты HTTP и маппинг |
| **Integration** | приложение + настоящий PostgreSQL | `@SpringBootTest` + Testcontainers | всё, что касается данных |
| **E2E** | полный HTTP-путь | `RANDOM_PORT` + `TestRestTemplate`/`WebTestClient` | ключевые сценарии |

Для клиентской разработки привычна широкая база unit-тестов и узкая верхушка. В backend с финансовыми данными **основные гарантии живут не в коде**, а в constraints, транзакциях и блокировках, — поэтому доля интеграционных тестов существенно выше, и это правильно.

### 1.2 Роль моков

Мок репозитория проверяет, что сервис вызвал метод. Он **не** проверяет, что данные сохранились, что constraint сработал, что параллельная транзакция не потеряла обновление. Моки уместны для внешних сервисов (неделя 11) и для изоляции чистой логики — не для базы.

---

## 2. Почему не H2

Соблазн понятен: H2 стартует за миллисекунды. Но в PostgreSQL-совместимом режиме он не воспроизводит:

- MVCC и реальную семантику уровней изоляции (write skew, `40001`);
- `SELECT ... FOR UPDATE SKIP LOCKED` и поведение блокировок;
- обнаружение дедлоков и `40P01`;
- `EXPLAIN`/планы — значит, проверять индексы невозможно;
- `jsonb`, `inet`, `timestamptz`, массивы, `interval`;
- партиальные, expression, GIN/BRIN индексы;
- точную семантику `ON CONFLICT` и `RETURNING`;
- коды `SQLSTATE`, на которые вы завязали обработку ошибок;
- поведение Flyway-миграций с PostgreSQL-специфичным DDL.

Результат: тест на H2 может пройти там, где PostgreSQL упадёт, и наоборот. Для темы этого курса H2 непригоден.

---

## 3. Testcontainers

### 3.1 Базовая настройка

```kotlin
@SpringBootTest
@Testcontainers
abstract class IntegrationTest {
    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("fintech")

        @DynamicPropertySource
        @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }
}
```

Версия образа — **та же, что в проде**. Тестировать на 17, деплоить на 15 — значит не тестировать.

### 3.2 Singleton-контейнер

`@Testcontainers` на каждом классе поднимает контейнер заново. На двадцати тестовых классах это минуты. Правильный приём — один контейнер на JVM:

```kotlin
object Postgres {
    val instance: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:17-alpine").apply {
        withReuse(true)
        start()          // остановится вместе с JVM (Ryuk уберёт контейнер)
    }
}
```

Локально включается reuse (`~/.testcontainers.properties`: `testcontainers.reuse.enable=true`) — контейнер переживает прогоны. В CI reuse обычно не нужен.

### 3.3 Ускорение

- `postgres:17-alpine` вместо полного образа.
- Отключить `fsync` **только в тестовом контейнере**: `withCommand("postgres", "-c", "fsync=off", "-c", "full_page_writes=off")`. Это законно ровно потому, что данные одноразовые.
- Общий Spring-контекст между тестовыми классами (не менять `@MockBean`-состав без нужды — иначе контекст пересоздаётся).
- `tmpfs` для каталога данных.

---

## 4. Изоляция состояния между тестами

### 4.1 Варианты

| Способ | Как | Ограничение |
|---|---|---|
| `@Transactional` на тесте | Spring откатывает после теста | **не видно другим соединениям** → конкурентные тесты невозможны; не проверяет то, что происходит на коммите (deferred constraints, триггеры) |
| `TRUNCATE` между тестами | `TRUNCATE t1, t2 RESTART IDENTITY CASCADE` | нужно перечислить таблицы (можно собрать из `information_schema`) |
| Пересоздание схемы | `DROP SCHEMA public CASCADE` + Flyway | медленнее, зато полностью честно |
| Схема на тестовый класс | `search_path` | сложнее, зато параллелизуемо |

Для этой недели рабочий выбор — `TRUNCATE` через `@AfterEach`, потому что конкурентные тесты требуют настоящих коммитов.

### 4.2 Фикстуры

Данные готовит явный builder/фабрика, а не `import.sql` на 500 строк. Тест должен читаться как «дано: счёт с балансом 1000», а не «дано: файл где-то в ресурсах».

---

## 5. Тестирование миграций и constraints

### 5.1 Миграции

Обязательный шаг CI (неделя 13): применить **все** миграции на пустой базе с нуля.

```kotlin
@Test
fun `migrations apply from scratch`() {
    val flyway = Flyway.configure().dataSource(url, user, pass).load()
    flyway.clean()
    val result = flyway.migrate()
    assertThat(result.migrationsExecuted).isGreaterThan(0)
}
```

Дополнительно полезно: применить миграции поверх дампа схемы предыдущего релиза — это ловит несовместимые изменения.

### 5.2 Constraints

Тест должен **нарушать** ограничение и ожидать конкретную ошибку:

```kotlin
@Test
fun `duplicate idempotency key is rejected by unique constraint`() {
    repo.insertKey("k-1", userId)
    assertThatThrownBy { repo.insertKey("k-1", userId) }
        .isInstanceOf(DuplicateKeyException::class.java)
}

@Test
fun `negative balance is rejected by check constraint`() {
    assertThatThrownBy { jdbc.update("UPDATE accounts SET balance_minor = -1 WHERE id = ?", accountId) }
        .isInstanceOf(DataIntegrityViolationException::class.java)
}

@Test
fun `ledger entry requires existing account`() { /* 23503 */ }
```

**Проверка теста:** удалите constraint из миграции — тест обязан упасть. Это единственный способ убедиться, что он проверяет базу, а не приложение.

### 5.3 Планы запросов

Для критичных запросов — тест, проверяющий отсутствие полного сканирования:

```kotlin
@Test
fun `payment history uses index`() {
    seedPayments(200_000)
    jdbc.execute("ANALYZE payments")
    val plan = jdbc.queryForList("EXPLAIN (ANALYZE, BUFFERS) $HISTORY_SQL", params)
        .joinToString("\n") { it.values.first().toString() }
    assertThat(plan).doesNotContain("Seq Scan on payments")
}
```

Держите утверждение узким (отсутствие Seq Scan по конкретной таблице, `Heap Fetches: 0`), иначе тест сломается на смене версии PostgreSQL.

---

## 6. Конкурентные тесты

### 6.1 Детерминированность без sleep

`Thread.sleep` даёт flaky-тесты: на быстрой машине один результат, на загруженном CI — другой. Используйте примитивы синхронизации.

**Одновременный старт N операций:**

```kotlin
@Test
fun `50 concurrent transfers preserve total balance`() {
    val threads = 50
    val pool = Executors.newFixedThreadPool(threads)
    val start = CountDownLatch(1)
    val done = CountDownLatch(threads)
    val errors = ConcurrentLinkedQueue<Throwable>()

    val totalBefore = totalMoney()

    repeat(threads) {
        pool.submit {
            try {
                start.await()
                transferService.transfer(from = a, to = b, amount = 100)
            } catch (e: InsufficientFunds) {
                // легальный исход
            } catch (e: Throwable) {
                errors += e
            } finally {
                done.countDown()
            }
        }
    }

    start.countDown()
    assertThat(done.await(30, TimeUnit.SECONDS)).isTrue()
    pool.shutdown()

    assertThat(errors).isEmpty()
    assertThat(totalMoney()).isEqualTo(totalBefore)      // главный инвариант
    assertThat(balanceOf(a)).isGreaterThanOrEqualTo(0)
    assertThat(ledgerSum(a)).isEqualTo(balanceOf(a))
}
```

Важно: `InsufficientFunds` — **легальный** результат при конкуренции, ошибкой не является. Тест проверяет инвариант, а не то, что все 50 переводов прошли.

Помните про размер пула соединений: 50 потоков и пул на 10 означают ожидание — это нормально, но `connection-timeout` должен быть больше времени теста, иначе получите ложные падения.

### 6.2 Строгий порядок шагов (lost update, deadlock)

Для воспроизведения аномалии нужен точный порядок операций в двух транзакциях — здесь помогает `CyclicBarrier`:

```kotlin
val barrier = CyclicBarrier(2)

// T1: BEGIN; UPDATE acc1; [barrier] UPDATE acc2; COMMIT
// T2: BEGIN; UPDATE acc2; [barrier] UPDATE acc1; COMMIT   → deadlock
```

Тест на дедлок утверждает: одна из транзакций получила `DeadlockLoserDataAccessException`, а инвариант при этом не нарушен. После внедрения единого порядка блокировок (неделя 7) тот же тест должен показывать отсутствие дедлока — это регрессионная защита.

### 6.3 Retry после serialization failure

```kotlin
@Test
fun `serialization failure is retried and operation succeeds once`() {
    // два параллельных Serializable-перевода по одному счёту
    // ожидание: обе операции завершились, retry сработал,
    //           итоговый баланс уменьшился ровно на 2 × amount
    assertThat(retryCounter.count()).isGreaterThan(0)   // retry действительно происходил
}
```

Проверять стоит и то, что retry **случился** (иначе тест не о том), и то, что результат корректен.

### 6.4 Идемпотентность

```kotlin
@Test
fun `parallel posts with same idempotency key create single transfer`() {
    val key = UUID.randomUUID().toString()
    val results = runParallel(10) { api.transfer(key, from, to, 100) }

    assertThat(countTransfers(from, to)).isEqualTo(1)
    assertThat(results.map { it.transferId }.distinct()).hasSize(1)
}
```

Плюс отдельный тест «повтор после таймаута»: первый запрос завершился, ответ не дошёл, клиент повторил — сервер вернул тот же результат, второй операции нет.

### 6.5 Борьба с flaky

- никаких `sleep` и зависимостей от времени;
- фиксированный `Clock` в бинах;
- явные таймауты у `await` и `assertThat(latch.await(...)).isTrue()`;
- независимость от порядка выполнения тестов;
- при параллельном запуске тестов — изоляция по схемам или отключение параллелизма для конкурентных классов;
- flaky-тест либо чинится, либо удаляется: «перезапустим CI» — это отказ от проверки.

---

## 7. Invariant-based тестирование

Вместо проверки конкретного результата проверяется **свойство**, которое должно выполняться всегда:

1. **Сохранение денег.** `sum(balance_minor)` по всем счетам неизменна после любого числа переводов.
2. **Неотрицательность.** Ни один баланс не отрицателен ни в какой момент.
3. **Согласованность projection.** `accounts.balance_minor = sum(ledger_entries.amount_minor)` для каждого счёта.
4. **Сбалансированность проводок.** `sum(amount_minor) = 0` по каждому `transfer_id`.
5. **Идемпотентность.** Число операций с одним ключом равно 1.
6. **Иммутабельность ledger.** Число проводок только растёт; никакая существующая не изменилась (проверяется хешем или счётчиком).

Дальнейший шаг — property-based тесты (kotest property, jqwik): генерировать случайные последовательности операций и проверять инварианты после каждой. Это ловит сценарии, которые не приходят в голову.

---

## 8. Smoke tests перед деплоем

Минимальный набор, который должен пройти на развёрнутом окружении (неделя 13):

- `/health` и `/actuator/health/readiness` отвечают;
- миграции применены (`flyway_schema_history` содержит ожидаемую версию);
- один чтения-endpoint возвращает данные;
- один пишущий сценарий проходит на техническом аккаунте и откатывается/помечается;
- версия приложения в ответе соответствует ожидаемой.

---

## 9. Типичные ошибки недели

1. H2 «для скорости».
2. Мок репозитория там, где проверяется поведение базы.
3. `@Transactional` на конкурентном тесте.
4. `Thread.sleep` вместо барьеров.
5. Тест конкурентности, который проверяет «не было исключений», а не инвариант.
6. Контейнер поднимается на каждый тестовый класс.
7. Версия PostgreSQL в тестах отличается от прода.
8. Тест не падает при удалении constraint.
9. Флаки перезапускаются вместо починки.
10. Проверка `EXPLAIN` на 100 строках.
11. Пул соединений меньше числа потоков теста без учёта таймаутов.

---

## 10. Критерий готовности

- Тесты падают при удалении constraint или блокировки и проходят после восстановления.
- Нет зависимости от локально установленной базы — всё поднимается Testcontainers.
- Есть параллельный тест перевода, проверяющий инвариант суммы.
- Есть тест retry после serialization failure и тест идемпотентности.
- Миграции применяются на пустой базе в CI.
- Определён минимальный набор smoke tests перед деплоем.

## 11. Официальные материалы

- Testcontainers for Java — PostgreSQL Module, Singleton containers, Reuse.
- Spring Boot Reference — Testing (`@SpringBootTest`, test slices, `@DynamicPropertySource`, `@ServiceConnection`).
- JUnit 5 User Guide — Parallel execution, Timeouts.
- PostgreSQL: Chapter 13 — Concurrency Control (для формулировки инвариантов).
- Flyway — Test migrations.
