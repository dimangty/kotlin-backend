# Неделя 12 — подробная теория

Логи, метрики и диагностика производительности.

> Критерий недели формулируется как вопрос: **произошёл инцидент — сможете ли вы за 10 минут сказать, какой запрос замедлился, из-за чего и кого он блокировал?** Всё содержимое файла существует ради этого ответа.

---

## 1. Structured logging

### 1.1 Событие вместо строки

```
// плохо
log.info("Transfer done for user " + userId + " amount " + amount)

// хорошо
log.info("transfer.completed", kv("userId", userId), kv("amount", amount), kv("durationMs", ms))
```

Строка не фильтруется и не агрегируется. Событие с полями позволяет спросить систему логов: «покажи все `transfer.completed` с `durationMs > 1000` за последний час».

Формат вывода — JSON (в Spring Boot 4.1 есть встроенная поддержка structured logging; классический вариант — `logstash-logback-encoder`). В консоль разработчика можно оставлять человекочитаемый формат, в проде — JSON.

### 1.2 Уровни

| Уровень | Когда |
|---|---|
| `ERROR` | нарушен контракт, требуется вмешательство человека |
| `WARN` | ситуация обработана, но ненормальна (retry, деградация, circuit open) |
| `INFO` | бизнес-события: операция создана, завершена, отклонена |
| `DEBUG` | детали для расследования, выключено в проде |
| `TRACE` | SQL, тела запросов — только локально |

Антипаттерн: `ERROR` на ожидаемую бизнес-ситуацию («недостаточно средств»). Это `INFO`/`WARN` — иначе алерты обесцениваются шумом.

### 1.3 MDC

```kotlin
class RequestIdFilter : OncePerRequestFilter() {
    override fun doFilterInternal(req: HttpServletRequest, res: HttpServletResponse, chain: FilterChain) {
        val requestId = req.getHeader("X-Request-Id") ?: UUID.randomUUID().toString()
        MDC.put("requestId", requestId)
        res.setHeader("X-Request-Id", requestId)
        try {
            chain.doFilter(req, res)
        } finally {
            MDC.clear()          // ОБЯЗАТЕЛЬНО: поток вернётся в пул
        }
    }
}
```

Правила:

1. **Всегда очищать в `finally`.** Иначе `requestId` предыдущего запроса попадёт в логи следующего — и расследование инцидента уведёт вас не туда.
2. **Ограниченный набор ключей.** MDC — не свалка; договоритесь о списке: `requestId`, `userId`, `operation`, `operationId`.
3. **Никаких секретов и ПДн.** MDC попадает в каждую запись — утечка будет массовой.
4. **Смена потока теряет MDC.** Для `@Async` — `TaskDecorator`, для корутин — `MDCContext` из `kotlinx-coroutines-slf4j` (неделя 11).

### 1.4 Что нельзя логировать

Пароли, токены (в том числе усечённые), содержимое `Authorization`, номера карт и счетов целиком, полные тела запросов с персональными данными, секреты из конфигурации.

Практический приём: белый список полей для логирования и маскирование по умолчанию. Проверка — намеренно поискать токен в выводе тестового прогона (неделя 9).

---

## 2. Корреляция: request, operation, trace

### 2.1 Три идентификатора

| ID | Область | Пример использования |
|---|---|---|
| `requestId` | один HTTP-запрос | связать лог, ответ и SQL |
| `traceId` / `spanId` | цепочка вызовов между сервисами | распределённая трассировка |
| `operationId` | одна бизнес-операция | перевод, переживший retry и два запроса |

`operationId` важен именно для финтеха: клиент повторил `POST` после таймаута — это **два** `requestId`, но **одна** операция. Логически связать их позволяет `operationId`, естественным кандидатом на который является idempotency key (неделя 7).

### 2.2 Возврат клиенту

`requestId` возвращается в заголовке ответа и в теле ошибки (`ApiError.requestId`, неделя 2). Тогда обращение в поддержку начинается не с «у меня не работает», а с конкретного идентификатора.

### 2.3 Связь с логами PostgreSQL

Логи приложения и логи БД нужно уметь сшить. Способы:

- **`application_name`** в JDBC URL — попадает в `pg_stat_activity` и в логи базы;
- **комментарий в SQL**: `/* requestId=abc123 */ SELECT ...` — виден в `pg_stat_activity.query` и в логе медленных запросов. Учтите: `pg_stat_statements` нормализует запросы, и разные комментарии могут размножить записи — используйте аккуратно.

### 2.4 Tracing

OpenTelemetry / Micrometer Tracing дают `traceId`, автоматически проходящий через HTTP-клиенты, и span'ы вокруг вызовов БД. Для одного монолита это опционально, но `traceId` в MDC стоит завести сразу — иначе переход к нескольким сервисам потребует переписывания логирования.

---

## 3. Метрики

### 3.1 Четыре золотых сигнала

- **Latency** — время ответа, обязательно по перцентилям и отдельно для успешных и неуспешных запросов.
- **Traffic** — RPS.
- **Errors** — доля 5xx и бизнес-ошибок.
- **Saturation** — насколько исчерпаны ресурсы: пул соединений, пул потоков, CPU, память, диск.

Saturation — самый недооценённый. Latency растёт **после** того, как ресурс насытился; метрика насыщения предупреждает раньше.

### 3.2 Типы метрик Micrometer

| Тип | Для чего | Пример |
|---|---|---|
| `Counter` | монотонно растущее число событий | число retry, число дедлоков |
| `Timer` | длительность + счётчик | время обработки перевода |
| `Gauge` | мгновенное значение | размер очереди, число `PENDING` |
| `DistributionSummary` | распределение величин | размер суммы перевода |

```kotlin
@Service
class TransferMetrics(registry: MeterRegistry) {
    private val timer = Timer.builder("transfer.duration")
        .publishPercentiles(0.5, 0.95, 0.99)
        .publishPercentileHistogram()
        .register(registry)

    private val retries = Counter.builder("transfer.retries")
        .description("serialization/deadlock retries")
        .register(registry)

    fun <T> timed(block: () -> T): T = timer.recordCallable(block)!!
    fun retry() = retries.increment()
}
```

### 3.3 Кардинальность

Каждое уникальное сочетание значений тегов — отдельный временной ряд. Правила:

- **Нельзя** тегировать `userId`, `accountId`, `requestId`, `transferId`, URL с подставленными параметрами.
- **Можно** тегировать endpoint-шаблон (`/accounts/{id}`), метод, статус, имя операции, исход (`SUCCESS`/`INSUFFICIENT_FUNDS`).

Взрыв кардинальности выводит из строя систему мониторинга — то есть вы теряете наблюдаемость ровно в тот момент, когда она нужнее всего.

### 3.4 Перцентили

Среднее скрывает хвост: при p50 = 20 мс и p99 = 3 с среднее может быть 50 мс и выглядеть прекрасно, хотя каждый сотый пользователь ждёт три секунды.

Технический нюанс: **перцентили нельзя усреднять** между инстансами. Правильно — публиковать гистограмму (`publishPercentileHistogram`) и считать квантиль на стороне Prometheus (`histogram_quantile(0.99, sum(rate(..._bucket[5m])) by (le))`).

### 3.5 Метрики, обязательные для этого проекта

| Метрика | Тип | Почему важна |
|---|---|---|
| `http.server.requests` (авто) | timer | latency/errors по endpoint |
| `hikaricp.connections.pending` | gauge | **насыщение пула**: кто-то ждёт соединение |
| `hikaricp.connections.active` / `.max` | gauge | загрузка пула |
| `hikaricp.connections.usage` | timer | как долго держим соединение = длительность транзакции |
| `transfer.duration` | timer | бизнес-операция |
| `transfer.retries`, `db.serialization_failures`, `db.deadlocks` | counter | конкурентность (недели 6-7) |
| `payments.pending.age.max` | gauge | зависшие операции (неделя 11) |
| `circuitbreaker.state` | gauge | состояние внешних интеграций |
| `jvm.memory.*`, `jvm.gc.*`, `jvm.threads.*` (авто) | — | ресурсы процесса |

---

## 4. Actuator и Prometheus

### 4.1 Actuator

```yaml
management:
  endpoints.web.exposure.include: health,info,metrics,prometheus
  endpoint.health:
    probes.enabled: true
    show-details: when-authorized
  metrics.tags.application: fintech
```

- `/actuator/health/liveness` — процесс жив (перезапускать при провале).
- `/actuator/health/readiness` — готов принимать трафик (исключить из балансировки при провале). Это разные вещи, и путать их дорого (неделя 13).
- **Actuator закрывается авторизацией** и не публикуется наружу: `/actuator/env`, `/actuator/heapdump` — источник утечки конфигурации.

Health-индикатор базы полезен, но не должен выполнять тяжёлый запрос: проверка readiness не может стоить дороже обычного запроса.

### 4.2 Prometheus

Pull-модель: Prometheus сам ходит на `/actuator/prometheus`. Полезные запросы PromQL:

```promql
# p99 latency по endpoint
histogram_quantile(0.99, sum(rate(http_server_requests_seconds_bucket[5m])) by (le, uri))

# доля 5xx
sum(rate(http_server_requests_seconds_count{status=~"5.."}[5m]))
  / sum(rate(http_server_requests_seconds_count[5m]))

# насыщение пула
max_over_time(hikaricp_connections_pending[5m])
```

---

## 5. Диагностика на стороне PostgreSQL

### 5.1 pg_stat_statements

```sql
CREATE EXTENSION IF NOT EXISTS pg_stat_statements;

SELECT calls,
       round(mean_exec_time::numeric, 2)  AS mean_ms,
       round(total_exec_time::numeric)    AS total_ms,
       rows,
       shared_blks_hit, shared_blks_read,
       left(query, 100) AS query
FROM pg_stat_statements
ORDER BY total_exec_time DESC
LIMIT 20;
```

Сортировка по `total_exec_time` — принципиальный момент: запрос на 3 мс, выполняемый 2 млн раз, съедает больше, чем запрос на 2 с раз в час.

`shared_blks_read` показывает реальный I/O; высокое отношение `read`/`hit` — признак того, что данные не помещаются в кеш или план читает лишнее.

`pg_stat_statements_reset()` перед экспериментом даёт чистую картину.

### 5.2 Медленные запросы и блокировки

```
log_min_duration_statement = 200ms
log_lock_waits = on
deadlock_timeout = 1s
log_autovacuum_min_duration = 0
log_temp_files = 0            # сортировки, ушедшие на диск
```

`log_temp_files` — недооценённая настройка: она показывает запросы, которым не хватило `work_mem` (external merge sort, hash batches).

### 5.3 Что происходит прямо сейчас

```sql
-- активность и ожидания
SELECT pid, state, wait_event_type, wait_event,
       now() - xact_start AS xact_age, left(query, 80)
FROM pg_stat_activity
WHERE backend_type = 'client backend' AND state <> 'idle'
ORDER BY xact_age DESC;

-- кто кого блокирует
SELECT blocked.pid, blocking.pid AS blocking_pid,
       left(blocked.query, 60) AS blocked_query,
       left(blocking.query, 60) AS blocking_query
FROM pg_stat_activity blocked
JOIN LATERAL unnest(pg_blocking_pids(blocked.pid)) AS b(pid) ON true
JOIN pg_stat_activity blocking ON blocking.pid = b.pid
WHERE blocked.wait_event_type = 'Lock';

-- bloat и vacuum
SELECT relname, n_live_tup, n_dead_tup, last_autovacuum, last_autoanalyze
FROM pg_stat_user_tables ORDER BY n_dead_tup DESC LIMIT 10;
```

### 5.4 EXPLAIN на боевых данных

Правило: план снимается на объёме, сравнимом с боевым. `EXPLAIN ANALYZE` на 10 строках всегда покажет Seq Scan и ничего не докажет (недели 4-5). Полезно уметь снять план с реальными параметрами из `pg_stat_statements` — он даёт нормализованный текст, значения подставляются вручную.

---

## 6. Разбор инцидента: latency выросла

Порядок действий, который должен стать рефлексом:

1. **Локализовать.** Все endpoint'ы или один? По метрике `http.server.requests` с тегом `uri`.
2. **Ресурс или запрос?** `hikaricp.connections.pending > 0` → ждём соединения (проблема в длительности транзакций или в размере пула). CPU/GC → проблема в приложении.
3. **Смотрим базу.** `pg_stat_activity`: много `wait_event_type = 'Lock'` → блокировки. Много активных тяжёлых запросов → план или объём.
4. **Блокировки.** `pg_blocking_pids` → кто держит. Смотрим, что делает блокирующая сессия. `idle in transaction` → баг приложения (транзакция открыта, а работа идёт вне базы — неделя 11).
5. **План.** `pg_stat_statements` → изменился ли `mean_exec_time` у конкретного запроса. Если да: устарела статистика (`ANALYZE`), сменился объём данных, изменился generic plan, пропал индекс.
6. **VACUUM.** `n_dead_tup` вырос, `last_autovacuum` давно → bloat и деградация чтения; заодно `Heap Fetches` в index-only scan.
7. **Связать с релизом.** Совпадает ли начало с деплоем или с миграцией.

Два типовых финала: **lock wait** (кто-то держит транзакцию слишком долго) и **плохой план** (статистика/данные изменились). Оба должны быть в вашем чек-листе с готовыми запросами.

---

## 7. Ограничения как часть диагностики

Диагностика без ограничений бесполезна: если у запросов нет предела, деградация превращается в отказ.

```sql
ALTER ROLE app SET statement_timeout = '10s';
ALTER ROLE app SET lock_timeout = '3s';
ALTER ROLE app SET idle_in_transaction_session_timeout = '30s';
```

Плюс на стороне приложения: `connection-timeout` HikariCP, `@Transactional(timeout = ...)`, таймауты HTTP-клиентов (неделя 11), ограничение размера страницы в API (неделя 2).

---

## 8. Лаборатория недели

1. Добавить `requestId` (фильтр + MDC + заголовок ответа + поле в `ApiError`) и `operationId` для переводов.
2. Настроить JSON-логи и убедиться, что поля попадают в каждую запись.
3. Проверить, что MDC очищается: два последовательных запроса не смешивают идентификаторы.
4. Экспортировать метрики: latency по endpoint, `hikaricp.connections.pending`, счётчики retry/deadlock/serialization failure, gauge зависших `PENDING`.
5. Подключить `pg_stat_statements`, включить `log_min_duration_statement` и `log_lock_waits`.
6. Сгенерировать нагрузку и найти один действительно медленный запрос на большой выборке; исправить (индекс/переписывание) и показать метрики до/после.
7. Смоделировать инцидент: удерживать транзакцию с блокировкой и пройти чек-лист из раздела 6 до причины.
8. Составить checklist для DB-инцидентов с готовыми SQL-запросами и положить его в `docs/`.
9. Провести аудит логов: убедиться, что нет токенов, паролей и ПДн.

---

## 9. Типичные ошибки недели

1. MDC без `clear()` в `finally`.
2. `userId`/`accountId` в тегах метрик.
3. Среднее время вместо перцентилей.
4. Усреднение перцентилей между инстансами.
5. Actuator без авторизации, доступный извне.
6. Логирование тел запросов с ПДн и токенами.
7. `ERROR` на ожидаемые бизнес-ситуации → алерты обесцениваются.
8. Метрики есть, алертов нет (или алерты на всё подряд).
9. `pg_stat_statements` сортируется по `mean_exec_time`.
10. Нет `statement_timeout` — «мы же следим за метриками».
11. Метрика saturation пула не собирается — исчерпание пула выглядит как «база тормозит».

---

## 10. Критерий готовности

- По логам и метрикам можно связать HTTP-запрос, транзакцию и SQL.
- Есть ограничение длительности запросов и транзакций.
- В логах нет секретов и персональных данных.
- Есть чек-лист разбора DB-инцидента с готовыми запросами.
- Найден и исправлен минимум один реально медленный запрос на большой выборке, с метриками до и после.

## 11. Официальные материалы

- Spring Boot Reference — Logging, Actuator, Metrics, Structured Logging.
- Micrometer — Concepts, Timers, Histograms and Percentiles, Naming and Tags.
- Prometheus — Querying (`rate`, `histogram_quantile`), Best practices on labels.
- PostgreSQL: `pg_stat_statements`, Chapter 28 — Monitoring Database Activity, Chapter 20.8 — Error Reporting and Logging.
- Google SRE Book — Monitoring Distributed Systems (four golden signals).
