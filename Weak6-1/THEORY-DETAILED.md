# Неделя 6-1 — подробная теория (Spring service + транзакции)

Транзакции, изоляция и retry со стороны сервисного слоя Kotlin/Spring.

Теория MVCC, уровней изоляции и аномалий подробно разобрана в [Weak6/THEORY-DETAILED.md](../Weak6/THEORY-DETAILED.md). Здесь — минимум и то, как это выглядит в приложении.

---

## 1. Минимум из SQL-части

- **Snapshot**: Read Committed берёт его на каждый оператор, Repeatable Read и Serializable — один на транзакцию.
- **Read Committed** допускает non-repeatable read, phantom и **lost update** при read-modify-write, разнесённом между `SELECT` и `UPDATE`. При этом атомарный `UPDATE ... SET x = x - n WHERE ...` безопасен: RC перечитывает строку и заново применяет `WHERE`.
- **Repeatable Read** (snapshot isolation) вместо lost update даёт `40001`; **write skew** не ловит.
- **Serializable** (SSI) ловит write skew ценой откатов; работает только если **все** участники Serializable, и требует retry в приложении.
- Dirty read в PostgreSQL невозможен.
- Повторять можно `40001` и `40P01`; нельзя — `23505`, `23503`, `23514`.

---

## 2. `@Transactional`: как это устроено

### 2.1 Прокси

Spring оборачивает бин в прокси (CGLIB для классов). Транзакция открывается, когда вызов проходит **через прокси**. Отсюда два классических провала:

```kotlin
@Service
class PaymentService(private val repo: PaymentRepository) {

    fun process(id: Long) {
        doProcess(id)        // ← self-invocation: транзакции НЕ будет
    }

    @Transactional
    fun doProcess(id: Long) { ... }
}
```

```kotlin
@Service
class PaymentService {
    @Transactional
    private fun hidden() { ... }   // private — прокси не перехватит
}
```

Kotlin-специфика: классы и методы в Kotlin по умолчанию `final`, а CGLIB-прокси требует наследования. Плагин `kotlin-allopen`, включённый стартером Spring, автоматически открывает классы с `@Component`/`@Service`/`@Transactional`. Если вы пишете свою аннотацию — не забудьте добавить её в конфигурацию плагина.

Как чинить self-invocation:

1. Вынести транзакционный метод в **отдельный бин** (предпочтительно — это ещё и честнее по слоям).
2. Инжектировать `self` (`@Lazy`) — работает, но выглядит как костыль.
3. Использовать `TransactionTemplate` — явно и без магии:

   ```kotlin
   transactionTemplate.execute { ... }
   ```

### 2.2 Атрибуты

```kotlin
@Transactional(
    isolation = Isolation.REPEATABLE_READ,
    propagation = Propagation.REQUIRED,
    timeout = 5,
    readOnly = false,
)
fun transfer(cmd: Transfer): TransferResult
```

- **`isolation`** — транслируется в `SET TRANSACTION ISOLATION LEVEL`. Можно задать и глобально: `spring.datasource.hikari.transaction-isolation`.
- **`propagation`**:
  - `REQUIRED` — присоединиться к существующей или открыть новую (умолчание);
  - `REQUIRES_NEW` — приостановить текущую и открыть новую. **Берёт второе соединение из пула.** При пуле в 10 и десяти параллельных запросах, каждый из которых открывает вложенную транзакцию, вы получите взаимную блокировку на `getConnection()`;
  - `NESTED` — через `SAVEPOINT`, в пределах одной транзакции;
  - `NOT_SUPPORTED` / `SUPPORTS` — для чтения вне транзакции;
  - `MANDATORY` — полезен для методов, которые обязаны вызываться внутри транзакции.
- **`readOnly = true`** — выполняет `SET TRANSACTION READ ONLY`, что для PostgreSQL действительно запрещает запись, и даёт Hibernate подсказку не делать dirty checking. Используйте на всех чисто читающих методах.
- **`timeout`** — ограничение длительности. Дополняйте настройками на стороне БД: `statement_timeout`, `lock_timeout`, `idle_in_transaction_session_timeout`.

### 2.3 Правила отката

По умолчанию Spring откатывает на `RuntimeException` и `Error`, но **не** на checked-исключениях. В Kotlin все исключения unchecked, поэтому поведение интуитивно. Но помните: если вы **поймали** исключение внутри транзакционного метода и не пробросили — транзакция закоммитится.

Отдельно: после того как в PostgreSQL произошла ошибка, транзакция aborted. Попытка продолжить работу в ней даст `25P02`. То есть «поймал и продолжил» внутри одной транзакции не работает без `SAVEPOINT` (в Spring — `Propagation.NESTED`).

### 2.4 Где открывать транзакцию

```
Controller           — нет транзакции
  Service            — @Transactional здесь: одна бизнес-операция = одна транзакция
    Repository       — нет своей транзакции, участвует в существующей
```

Причины:

- контроллер не должен держать транзакцию во время сериализации ответа;
- репозиторий с собственной транзакцией на каждый метод делает атомарность невозможной.

---

## 3. Что нельзя делать внутри транзакции

1. **HTTP-вызовы и любые сетевые ожидания.** Внешний сервис отвечает 30 секунд → соединение БД и все взятые блокировки удерживаются 30 секунд. Это самая дорогая ошибка проекта; тема недели 11.
2. **`Thread.sleep`, ожидание очередей, пользовательский ввод.**
3. **Тяжёлые вычисления**, которые можно сделать до открытия транзакции.
4. **Публикация событий, которые нельзя откатить** (отправка в брокер, письмо). Правильно — outbox: запись в таблицу в той же транзакции, отправка отдельным процессом.
5. **Чтение больших объёмов «на всякий случай»** — snapshot держится, VACUUM страдает.

Диагностика: `idle in transaction` в `pg_stat_activity` — почти всегда признак того, что приложение что-то делает, держа открытую транзакцию.

---

## 4. Безопасное списание: четыре реализации

Задание недели — сделать минимум две и сравнить.

### 4.1 Атомарный UPDATE

```kotlin
@Transactional
fun debit(accountId: Long, amount: Long) {
    val updated = jdbc.update(
        """
        UPDATE accounts
        SET balance_minor = balance_minor - :amount
        WHERE id = :id AND balance_minor >= :amount
        """,
        mapOf("id" to accountId, "amount" to amount),
    )
    if (updated == 0) throw InsufficientFunds(accountId)
}
```

Корректно даже на Read Committed. Максимальный throughput. Проверка `updated == 0` обязательна — иначе ошибка «недостаточно средств» пройдёт незамеченной.

### 4.2 Оптимистичная блокировка

```kotlin
@Transactional
fun debitOptimistic(account: Account, amount: Long) {
    val newBalance = account.balanceMinor - amount
    require(newBalance >= 0) { "insufficient funds" }
    val updated = jdbc.update(
        """
        UPDATE accounts SET balance_minor = :new, version = version + 1
        WHERE id = :id AND version = :version
        """,
        mapOf("new" to newBalance, "id" to account.id, "version" to account.version),
    )
    if (updated == 0) throw VersionConflict(account.id)   // → 409 или retry
}
```

Ровно тот механизм, который заложен на неделе 2 полем `version`. В JPA то же самое даёт `@Version` (неделя 8). Хорошо работает при низкой конкуренции и естественно ложится на HTTP (`If-Match` → `409`).

### 4.3 Пессимистичная блокировка

```kotlin
@Transactional
fun debitPessimistic(accountId: Long, amount: Long) {
    val balance = jdbc.queryForObject(
        "SELECT balance_minor FROM accounts WHERE id = :id FOR UPDATE",
        mapOf("id" to accountId), Long::class.java)!!
    if (balance < amount) throw InsufficientFunds(accountId)
    jdbc.update("UPDATE accounts SET balance_minor = :new WHERE id = :id",
        mapOf("new" to balance - amount, "id" to accountId))
}
```

Работает для любой логики, но сериализует доступ к строке. Обязателен `lock_timeout` — иначе запрос будет ждать бесконечно. При блокировке нескольких строк нужен единый порядок захвата (неделя 7).

### 4.4 Serializable + retry

```kotlin
@Transactional(isolation = Isolation.SERIALIZABLE)
fun debitSerializable(accountId: Long, amount: Long) {
    val balance = ...  // обычный SELECT
    if (balance < amount) throw InsufficientFunds(accountId)
    jdbc.update("UPDATE accounts SET balance_minor = :new WHERE id = :id", ...)
}
```

Логика пишется наивно, корректность обеспечивает СУБД. Обязателен retry уровнем выше.

### 4.5 И в любом случае

`CHECK (balance_minor >= 0)` в схеме. Это не замена ни одному из способов, а страховка от ошибки в коде.

---

## 5. Retry в Kotlin

### 5.1 Правильное расположение слоёв

```
HTTP endpoint
  → RetryFacade            ← retry ЗДЕСЬ
      → @Transactional Service   ← транзакция целиком внутри одной попытки
```

Retry обязан быть **снаружи** транзакции: прокси должен успеть сделать rollback, прежде чем начнётся новая попытка. Retry внутри транзакционного метода бесполезен — транзакция уже aborted.

### 5.2 Реализация

```kotlin
private val RETRYABLE = setOf("40001", "40P01")

fun <T> retryOnConflict(maxAttempts: Int = 4, block: () -> T): T {
    var attempt = 0
    while (true) {
        try {
            return block()
        } catch (e: DataAccessException) {
            val sqlState = (e.rootCause as? SQLException)?.sqlState
            attempt++
            if (sqlState !in RETRYABLE || attempt >= maxAttempts) throw e
            val backoff = (50L shl attempt) + Random.nextLong(50)   // backoff + jitter
            log.warn("retrying after {} (attempt {}), sqlState={}", backoff, attempt, sqlState)
            Thread.sleep(backoff)
        }
    }
}
```

Альтернатива — Spring Retry (`@Retryable(retryFor = [CannotSerializeTransactionException::class])`), но следите, чтобы `@Retryable` и `@Transactional` не оказались на одном методе: тогда порядок прокси определит, работает ли схема вообще. Разносите по разным бинам.

Соответствие исключений:

| SQLSTATE | Spring-исключение |
|---|---|
| 40001 | `CannotSerializeTransactionException` |
| 40P01 | `DeadlockLoserDataAccessException` |
| 55P03 | `CannotAcquireLockException` |
| 23505 | `DuplicateKeyException` — **не повторять** |

### 5.3 Retry и идемпотентность

Повторяемая операция обязана быть идемпотентной. Если между попытками возможен побочный эффект (запись, отправка), повтор создаст дубль. Отсюда прямой мостик к неделе 7: `Idempotency-Key` + `UNIQUE` constraint.

### 5.4 Наблюдаемость

Каждый retry — событие: логировать `requestId`, номер попытки, SQLSTATE. Счётчик retry и доля откатов — метрики, за которыми смотрят (неделя 12). Рост `40001` означает либо всплеск конкуренции, либо неудачную модель данных.

---

## 6. Лаборатория недели

1. Воспроизвести non-repeatable read и lost update в двух psql-сессиях (`session-a.sql` / `session-b.sql`), записать timeline.
2. Повторить lost update **через API**: два параллельных запроса к endpoint'у, реализованному через read-modify-write. Показать, что деньги потерялись.
3. Переписать endpoint атомарным `UPDATE` и показать, что тест больше не падает.
4. Реализовать оптимистичный вариант и вернуть `409` при конфликте версий.
5. Получить `40001` на Repeatable Read/Serializable и реализовать ограниченный retry.
6. Написать параллельный тест: N потоков списывают с одного счёта, инвариант — баланс никогда не отрицателен и суммарно списано ровно N × amount либо часть попыток честно отклонена.
7. Продемонстрировать self-invocation: метод без транзакции, где данные частично сохранились.

---

## 7. Типичные ошибки недели

1. Self-invocation `@Transactional` — «транзакция вроде есть, а отката нет».
2. Retry внутри транзакционного метода.
3. Read-modify-write в сервисе вместо атомарного `UPDATE`.
4. Serializable без retry.
5. `REQUIRES_NEW` в цикле → исчерпание пула соединений.
6. Проглоченное исключение внутри `@Transactional` → неожиданный коммит.
7. HTTP-вызов внутри транзакции.
8. Отсутствие проверки числа затронутых строк после условного `UPDATE`.
9. Транзакция, открытая в контроллере и живущая всё время сериализации ответа.

---

## 8. Критерий готовности

- Предсказываете видимость строк до эксперимента.
- Знаете, какие ошибки нужно повторять автоматически, а какие нельзя.
- Реализовали безопасное списание минимум двумя способами и сравнили их.
- Параллельный тест не нарушает инвариант баланса.
- Можете показать, где именно в вашем коде начинается и заканчивается транзакция.

## 9. Официальные материалы

- PostgreSQL: Chapter 13 — Concurrency Control; Appendix A — Error Codes (класс 40).
- Spring Framework — Data Access: Transaction Management (`@Transactional`, propagation, proxy mode).
- Spring Framework — `TransactionTemplate`.
- Spring Retry — reference.
- Kotlin — `kotlin-allopen` plugin.
