# Неделя 11 — подробная теория

Корутины, внешние сервисы и отказоустойчивость.

> Главный тезис недели: **сеть ненадёжна, и это не исключительная ситуация, а нормальный режим работы.** Задача — построить систему, в которой потерянный ответ, таймаут и повтор не приводят к двойному платежу и не роняют базу.

---

## 1. Structured concurrency

### 1.1 Scope и иерархия

Корутина всегда запускается в `CoroutineScope`. Scope владеет job'ами детей и не завершается, пока они живы. Это устраняет утечку задач: нет «висящих» корутин, о которых никто не знает.

```kotlin
suspend fun loadDashboard(userId: Long): Dashboard = coroutineScope {
    val accounts = async { accountService.list(userId) }
    val rates    = async { ratesClient.today() }
    Dashboard(accounts.await(), rates.await())
}
```

- `coroutineScope` — ошибка любого ребёнка отменяет всех остальных и пробрасывается наружу. То, что нужно, когда результат бессмысленен без каждой части.
- `supervisorScope` — сбой ребёнка не отменяет соседей. Нужно, когда часть данных опциональна.

`GlobalScope` — почти всегда ошибка: такая корутина не связана с жизненным циклом запроса и переживёт его.

### 1.2 Отмена кооперативна

Отмена ставит job в состояние «cancelling»; `CancellationException` бросается **на suspend-точке**. Отсюда:

- бесконечный цикл без suspend-вызовов не отменяется (`ensureActive()` / `yield()` спасают);
- **блокирующий вызов (JDBC, `Thread.sleep`, синхронный HTTP) отменить нельзя** — корутина «отменена», а поток продолжает ждать.

`CancellationException` нельзя глотать:

```kotlin
try {
    externalCall()
} catch (e: CancellationException) {
    throw e                       // обязательно пробросить
} catch (e: Exception) {
    log.warn("failed", e)
}
```

Ловля `Exception` без этой ветки ломает отмену — типичная ошибка.

Освобождение ресурсов после отмены:

```kotlin
try {
    doWork()
} finally {
    withContext(NonCancellable) { releaseResources() }   // suspend-вызовы в finally
}
```

### 1.3 Context propagation

`CoroutineContext` переносит Job, dispatcher и элементы вроде `MDCContext`. Что **не** переносится автоматически:

- `SecurityContextHolder` (ThreadLocal) — принципал теряется при смене потока;
- транзакционный контекст Spring (тоже ThreadLocal);
- MDC для логов — нужен `MDCContext` из `kotlinx-coroutines-slf4j`.

Практическое правило: **передавайте `userId`, `requestId` и прочий контекст явными параметрами**, а не через ThreadLocal. Для логов подключайте `MDCContext` осознанно.

---

## 2. Блокирующий JDBC и выбор dispatcher

### 2.1 Дисперчеры

| Dispatcher | Потоки | Назначение |
|---|---|---|
| `Dispatchers.Default` | по числу ядер | CPU-работа |
| `Dispatchers.IO` | до 64 (настраивается) | блокирующий I/O |
| `Dispatchers.Unconfined` | — | почти никогда не нужен |
| собственный пул | сколько задали | изоляция (bulkhead) |

Блокирующий вызов на `Dispatchers.Default` занимает поток, которых всего N по числу ядер, — это быстро парализует всю CPU-работу приложения.

### 2.2 JDBC

```kotlin
suspend fun findAccount(id: Long): Account = withContext(Dispatchers.IO) {
    repository.findById(id)          // блокирующий вызов, но на IO-пуле
}
```

Соотношение размеров: если пул соединений 10, то 64 потока IO, одновременно пытающиеся получить соединение, просто создадут очередь. Отдельный ограниченный пул под БД честнее:

```kotlin
val dbDispatcher = Executors.newFixedThreadPool(10).asCoroutineDispatcher()
```

### 2.3 Транзакции и корутины

Транзакция Spring привязана к потоку через `TransactionSynchronizationManager` (ThreadLocal). Отсюда жёсткое правило:

```kotlin
// ПРАВИЛЬНО: транзакция целиком внутри одного withContext, в одном потоке
suspend fun process(cmd: Cmd) = withContext(dbDispatcher) {
    transactionTemplate.execute { doWork(cmd) }
}

// НЕПРАВИЛЬНО: транзакция открыта, а внутри suspend-вызовы, меняющие поток
@Transactional
suspend fun process(cmd: Cmd) {
    val a = repo.load()
    val b = externalClient.call()      // смена потока И сетевой вызов в транзакции
    repo.save(...)
}
```

`@Transactional` на `suspend`-функции — источник трудноуловимых ошибок. Держите транзакционные методы обычными (блокирующими) и вызывайте их из `withContext`.

---

## 3. Таймауты

### 3.1 Три уровня

1. **Connect timeout** — установление TCP/TLS.
2. **Read / response timeout** — ожидание ответа.
3. **Бюджет операции** — общее время, включая повторы (`withTimeout`).

```kotlin
val client = WebClient.builder()
    .clientConnector(ReactorClientHttpConnector(
        HttpClient.create()
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 2000)
            .responseTimeout(Duration.ofSeconds(3))
    ))
    .build()

suspend fun charge(req: ChargeRequest): ChargeResult =
    withTimeout(5.seconds) {                 // бюджет всей операции
        client.post()...awaitBody()
    }
```

`withTimeout` работает только для отменяемых (suspend) вызовов. Для блокирующего клиента таймаут обязан быть на уровне самого клиента.

### 3.2 Бюджет сверху вниз

Если ваш API обещает ответ за 3 с, внутренние вызовы не могут суммарно занимать 10 с. Распределяйте бюджет и **передавайте оставшееся время вниз**. Иначе клиент отваливается по таймауту, а вы продолжаете работу, которая уже никому не нужна.

Полезное следствие: при отмене со стороны клиента (`ClientAbortException`) стоит прекращать работу, а не доводить её до конца.

### 3.3 Таймауты на стороне БД

Дополняют клиентские: `statement_timeout`, `lock_timeout`, `idle_in_transaction_session_timeout` (неделя 7), `spring.transaction.default-timeout`, HikariCP `connection-timeout`.

---

## 4. Retry, backoff, jitter

### 4.1 Что повторять

| Ситуация | Retry |
|---|---|
| Сетевая ошибка, connect timeout | да |
| HTTP 502/503/504 | да |
| HTTP 429 | да, с учётом `Retry-After` |
| HTTP 400/404/422 | **нет** |
| HTTP 409 | зависит от семантики |
| `40001`, `40P01` | да (неделя 6-7) |
| `23505`, `23503`, `23514` | **нет** |
| **Read timeout** | только если операция идемпотентна |

Read timeout — самый коварный случай: вы **не знаете**, выполнилась операция или нет. Повторять можно только с тем же idempotency key.

### 4.2 Backoff с jitter

```kotlin
suspend fun <T> retrying(
    maxAttempts: Int = 4,
    base: Duration = 100.milliseconds,
    budget: Duration = 5.seconds,
    isRetryable: (Throwable) -> Boolean,
    block: suspend () -> T,
): T = withTimeout(budget) {
    var attempt = 0
    while (true) {
        try {
            return@withTimeout block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            attempt++
            if (attempt >= maxAttempts || !isRetryable(e)) throw e
            val backoff = base * (1 shl attempt) + Random.nextInt(100).milliseconds  // full jitter
            delay(backoff)
        }
    }
}
```

Почему **jitter** обязателен: без него все клиенты, получившие ошибку одновременно, повторят одновременно — синхронная волна добьёт восстанавливающийся сервис («thundering herd»). Практичный вариант — full jitter: `random(0, base * 2^attempt)`.

Почему **бюджет** обязателен: retry без ограничения общего времени превращает деградацию в отказ — запросы копятся, потоки заняты, очередь растёт.

---

## 5. Circuit breaker и bulkhead

### 5.1 Circuit breaker

```
CLOSED  ──(доля ошибок > порога)──►  OPEN
  ▲                                    │
  │                            (истёк wait duration)
  │                                    ▼
  └──(пробные запросы успешны)──  HALF_OPEN
```

- **CLOSED** — запросы идут, статистика собирается в скользящем окне.
- **OPEN** — вызовы отклоняются **немедленно**, без обращения к сервису. Это защищает и партнёра (даём восстановиться), и себя (не тратим потоки на заведомо неудачные вызовы).
- **HALF_OPEN** — ограниченное число пробных запросов; успех → CLOSED, неудача → OPEN.

Важные детали: считать не только ошибки, но и **медленные вызовы** (slow call rate); обязательно иметь fallback-поведение на состояние OPEN (кешированный ответ, `503` с `Retry-After`, постановка в очередь); настраивать отдельный breaker на каждого партнёра.

### 5.2 Bulkhead

Изоляция ресурсов: у каждого внешнего партнёра свой ограниченный пул потоков или семафор. Без этого один медленный партнёр займёт все потоки и утащит за собой всё приложение — эффект каскадного отказа.

```kotlin
private val partnerSemaphore = Semaphore(20)

suspend fun call() = partnerSemaphore.withPermit { client.call() }
```

Bulkhead + timeout + retry + breaker работают только вместе: таймаут ограничивает одну попытку, retry борется с эпизодическими сбоями, breaker отключает сломанное, bulkhead не даёт одному сбою утопить остальные.

В экосистеме Spring это даёт Resilience4j (`@CircuitBreaker`, `@Bulkhead`, `@Retry`, `@TimeLimiter`), но понимать нужно механику, а не аннотации.

---

## 6. Транзакция и внешний вызов

### 6.1 Почему это главная ошибка

```kotlin
@Transactional
fun charge(cmd: Charge) {
    val account = repo.lockForUpdate(cmd.accountId)   // блокировка строки
    val result = paymentGateway.charge(cmd)           // 30 секунд ожидания
    repo.save(result)
}
```

Пока внешний сервис думает, вы держите: соединение из пула, открытую транзакцию (снапшот мешает VACUUM), блокировку на строке счёта. Замедление партнёра в десять раз мгновенно превращается в исчерпание пула соединений и лавину lock wait. Это самый быстрый способ уронить базу из приложения.

### 6.2 Правильная разбивка

```
TX1: INSERT payment (status = PENDING, idempotency_key, external_ref)
     COMMIT                                   ← намерение зафиксировано

сеть: gateway.charge(idempotencyKey = external_ref) с таймаутом и retry

TX2: UPDATE payment SET status = DONE/FAILED, external_id = ...
     INSERT ledger_entries ...
     UPDATE accounts ... (projection)
     COMMIT
```

Свойства такой схемы:

- ни одна транзакция не ждёт сеть;
- при падении между TX1 и TX2 остаётся запись `PENDING` — её подхватит reconciliation;
- ключ идемпотентности, отправленный партнёру, тот же самый — повтор не создаст второй платёж на его стороне.

### 6.3 Смоделировать сбой

Обязательное задание недели: сделать так, чтобы внешняя операция **успешно выполнилась**, а ответ не дошёл (таймаут). Затем повторить запрос и убедиться, что второго списания нет. Это ровно тот сценарий, ради которого существуют idempotency key и reconciliation.

---

## 7. At-least-once и reconciliation

### 7.1 Три семантики

- **At-most-once** — не более одного раза; возможна потеря. Для денег недопустимо.
- **At-least-once** — не менее одного раза; возможны дубликаты. Реалистичный режим сети.
- **Exactly-once** — недостижимо в распределённой системе как свойство доставки, но достижимо как **свойство эффекта**: at-least-once доставка + идемпотентная обработка.

Отсюда: не пытайтесь добиться «ровно одной доставки», добивайтесь «ровно одного эффекта».

### 7.2 Reconciliation

Фоновая задача, приводящая состояние в соответствие с внешней системой:

```sql
SELECT id, external_ref FROM payments
WHERE status = 'PENDING' AND created_at < now() - interval '2 minutes'
ORDER BY created_at
LIMIT 100
FOR UPDATE SKIP LOCKED;
```

Для каждой записи — запрос статуса у партнёра по `external_ref` и перевод в терминальное состояние. `SKIP LOCKED` (неделя 7) позволяет запускать несколько воркеров.

Без reconciliation любая запись, застрявшая в `PENDING`, остаётся там навсегда — деньги «зависают», и это обнаруживает не мониторинг, а клиент. Метрика «число PENDING старше N минут» должна быть на дашборде (неделя 12).

### 7.3 Outbox

Проблема: записать в БД и отправить событие атомарно нельзя — это две разные системы.

Решение: событие пишется в таблицу `outbox` **в той же транзакции**, что и бизнес-данные; отдельный процесс читает неотправленные (`FOR UPDATE SKIP LOCKED`), отправляет и помечает `sent_at`. Гарантия — at-least-once, поэтому потребитель обязан быть идемпотентным.

Саму saga/оркестрацию на этой неделе разбирать целиком не нужно — достаточно понимать outbox и компенсирующие операции (неделя 7).

---

## 8. Лаборатория недели

1. Интегрировать внешний сервис (можно заглушку с управляемой задержкой) с connect/read timeout и общим бюджетом.
2. Реализовать retry с exponential backoff и jitter; показать в логах интервалы повторов.
3. Разделить локальную транзакцию и внешний вызов по схеме TX1 → сеть → TX2.
4. Смоделировать: внешняя операция выполнена, ответ потерян по таймауту. Повторить запрос и доказать отсутствие дубля.
5. Реализовать reconciliation для `PENDING` старше N минут с `SKIP LOCKED`.
6. Добавить circuit breaker и показать переход CLOSED → OPEN → HALF_OPEN при недоступности партнёра.
7. Проверить отмену: клиент разорвал соединение → корутина отменена → нет зависших соединений БД.
8. Замерить использование потоков под нагрузкой; убедиться, что блокирующие вызовы не на `Dispatchers.Default`.

---

## 9. Типичные ошибки недели

1. Внешний HTTP внутри открытой транзакции.
2. `@Transactional` на `suspend`-функции.
3. Блокирующий JDBC на `Dispatchers.Default`.
4. `catch (e: Exception)`, глотающий `CancellationException`.
5. Retry без ограничения попыток и без бюджета времени.
6. Retry без jitter.
7. Retry неидемпотентной операции после read timeout → двойной платёж.
8. Отсутствие таймаута хотя бы на одном уровне.
9. `GlobalScope.launch` вместо scope запроса.
10. Расчёт на ThreadLocal (`SecurityContextHolder`, MDC) после смены потока.
11. Записи `PENDING` без reconciliation.

---

## 10. Критерий готовности

- Нет бесконечных retry и зависших соединений БД.
- Повтор запроса не создаёт двойной платёж — доказано тестом.
- Ни один сетевой вызов не находится внутри транзакции — доказано ревью и логами.
- Каждый внешний вызов имеет таймаут на всех уровнях и вписан в бюджет операции.
- Есть reconciliation и метрика «зависших» операций.

## 11. Официальные материалы

- Kotlin Coroutines Guide — Cancellation and Timeouts, Composing Suspending Functions, Coroutine Context and Dispatchers, Exception Handling.
- `kotlinx-coroutines-slf4j` — MDCContext.
- Spring Framework — WebClient, `RestClient`, Kotlin Coroutines support.
- Resilience4j — CircuitBreaker, Bulkhead, Retry, TimeLimiter.
- AWS Architecture Blog — Exponential Backoff and Jitter.
