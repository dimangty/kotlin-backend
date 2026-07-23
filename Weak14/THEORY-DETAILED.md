# Неделя 14 — подробная теория

Ktor: явный серверный стек для сравнения.

> Цель недели — не выбрать «лучший фреймворк», а увидеть границу между тем, что вы знаете о backend, и тем, что вы знали о Spring. Правильный итог: обнаружить, что бóльшая часть знаний последних тринадцати недель к фреймворку не относится.

---

## 1. Модель Ktor

### 1.1 Приложение как функция

```kotlin
fun main() {
    embeddedServer(Netty, port = 8080, host = "0.0.0.0") {
        module()
    }.start(wait = true)
}

fun Application.module() {
    configureSerialization()
    configureStatusPages()
    configureSecurity()
    configureRouting()
}
```

Здесь нет component scan, нет `@Bean`, нет условной автоконфигурации. Всё, что работает, написано в этих строках. Первое следствие: чтобы понять, что делает приложение, достаточно прочитать `module()`.

### 1.2 Pipeline и плагины

Обработка запроса в Ktor — конвейер с фазами (`Setup`, `Monitoring`, `Plugins`, `Call`, `Fallback`). **Плагин** — код, встраивающийся в фазу:

```kotlin
install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
install(CallLogging) { callIdMdc("requestId") }
install(CallId) { header(HttpHeaders.XRequestId); generate { UUID.randomUUID().toString() } }
install(StatusPages) { ... }
install(Authentication) { ... }
```

Аналогия со Spring: плагин ≈ фильтр/автоконфигурация, но включается явно и в явном порядке. Порядок установки имеет значение — это одновременно и источник ошибок, и источник понятности.

### 1.3 Модель исполнения

Ktor построен на корутинах и неблокирующем движке (Netty, CIO). Обработчик — `suspend`-функция. Это принципиально отличается от «поток на запрос» в Spring MVC (неделя 1):

- нет пула из 200 потоков, ожидающих I/O;
- зато **блокирующий вызов особенно вреден**: он занимает поток event loop.

Отсюда прямое следствие для недели 15: **JDBC блокирующий**, и его вызовы обязаны уходить на отдельный dispatcher (`Dispatchers.IO` или собственный пул, соразмерный пулу соединений). Всё, что разбиралось на неделе 11, применяется здесь по умолчанию, а не опционально.

---

## 2. Routing

### 2.1 Дерево маршрутов

```kotlin
fun Application.configureRouting(service: PaymentService) {
    routing {
        get("/health") { call.respond(mapOf("status" to "UP")) }

        route("/api") {
            authenticate("jwt") {
                route("/payments") {
                    get("/{id}") { call.respondPayment(service) }
                    post { call.createPayment(service) }
                }
                route("/accounts/{accountId}/ledger") {
                    get { call.respondLedger(service) }
                }
            }
        }
    }
}
```

Маршруты образуют дерево; плагины (`authenticate`, `rateLimit`) применяются к поддереву. Преимущество перед аннотациями: **вся карта API видна в одном файле**, и нельзя случайно оставить endpoint без авторизации, не заметив этого в дереве.

### 2.2 Параметры и тело

```kotlin
get("/{id}") {
    val id = call.parameters["id"]?.toLongOrNull()
        ?: return@get call.respond(HttpStatusCode.BadRequest, ApiError("BAD_REQUEST", "id must be numeric"))

    val userId = call.principal<UserPrincipal>()!!.userId
    val payment = service.findOwned(id, userId)
        ?: return@get call.respond(HttpStatusCode.NotFound, ApiError("NOT_FOUND", "payment not found"))

    call.respond(payment.toResponse())
}

post {
    val request = call.receive<CreatePaymentRequest>()
    val key = call.request.header("Idempotency-Key")
        ?: return@post call.respond(HttpStatusCode.BadRequest, ApiError("IDEMPOTENCY_KEY_REQUIRED", ...))
    val result = service.create(request.toCommand(), key)
    call.response.header(HttpHeaders.Location, "/api/payments/${result.id}")
    call.respond(HttpStatusCode.Created, result.toResponse())
}
```

Заметьте: ownership-проверка (неделя 9) встроена в запрос — `findOwned(id, userId)`. Фреймворк сменился, правило осталось.

### 2.3 Type-safe routing

Ktor предлагает `Resources` — типобезопасные маршруты через `@Serializable` классы. Приятно, но не обязательно; главное — не потерять читаемость дерева.

---

## 3. Плагины: соответствие Spring

### 3.1 Сериализация

```kotlin
install(ContentNegotiation) {
    json(Json {
        ignoreUnknownKeys = true      // важно для обратной совместимости (неделя 2)
        encodeDefaults = true
    })
}
```

kotlinx.serialization работает на **compile-time генерации**, а не на рефлексии: быстрее старт, меньше сюрпризов, но `@Serializable` нужно ставить явно, и не все типы поддерживаются из коробки (для `Instant`, `BigDecimal` пишутся сериализаторы).

`ignoreUnknownKeys = true` — прямое требование обратной совместимости: новый клиент прислал поле, которого старый сервер не знает, — это не повод падать.

### 3.2 StatusPages — единый error contract

```kotlin
install(StatusPages) {
    exception<ValidationException> { call, cause ->
        call.respond(HttpStatusCode.BadRequest, ApiError("VALIDATION_FAILED", cause.message, cause.details, call.callId))
    }
    exception<NotFoundException> { call, _ ->
        call.respond(HttpStatusCode.NotFound, ApiError("NOT_FOUND", "resource not found", requestId = call.callId))
    }
    exception<VersionConflict> { call, _ ->
        call.respond(HttpStatusCode.Conflict, ApiError("VERSION_CONFLICT", ..., requestId = call.callId))
    }
    exception<Throwable> { call, cause ->
        call.application.log.error("unhandled", cause)
        call.respond(HttpStatusCode.InternalServerError, ApiError("INTERNAL", "internal error", requestId = call.callId))
    }
}
```

Ровно та же структура, что и `@RestControllerAdvice` на неделе 2, включая правило «наружу не уходит stack trace».

### 3.3 Валидация

```kotlin
install(RequestValidation) {
    validate<CreatePaymentRequest> { req ->
        when {
            req.amountMinor <= 0 -> ValidationResult.Invalid("amount must be positive")
            req.currency.length != 3 -> ValidationResult.Invalid("currency must be ISO-4217")
            else -> ValidationResult.Valid
        }
    }
}
```

Более ручная работа, чем Bean Validation. Плюс: правило видно как код и легко тестируется. Минус: больше писать, легче забыть.

Напоминание из недели 3: валидация DTO **не заменяет** constraint в базе.

### 3.4 Authentication

```kotlin
install(Authentication) {
    jwt("jwt") {
        realm = "fintech"
        verifier(
            JWT.require(Algorithm.HMAC256(secret))   // алгоритм фиксирован в коде (неделя 9)
                .withIssuer(issuer)
                .withAudience(audience)
                .build()
        )
        validate { credential ->
            credential.payload.getClaim("userId").asLong()?.let { UserPrincipal(it) }
        }
        challenge { _, _ -> call.respond(HttpStatusCode.Unauthorized, ApiError("UNAUTHORIZED", ...)) }
    }
}
```

Все правила недели 9 остаются: короткий access, refresh с rotation и хранением хеша, ownership-проверки, никаких ПДн в payload, фиксированный алгоритм подписи.

Отличие от Spring Security: нет фильтровой цепочки с неявным порядком — есть явный `authenticate("jwt") { ... }` вокруг поддерева маршрутов. Меньше возможностей, зато меньше способов ошибиться незаметно.

### 3.5 Логи и корреляция

```kotlin
install(CallId) {
    header(HttpHeaders.XRequestId)
    generate { UUID.randomUUID().toString() }
    verify { it.isNotEmpty() }
    reply { call, callId -> call.response.header(HttpHeaders.XRequestId, callId) }
}
install(CallLogging) {
    callIdMdc("requestId")
    level = Level.INFO
}
```

Это ровно контракт недели 12: `requestId` из заголовка или сгенерированный, в MDC, в ответе и в теле ошибки. В корутинном окружении `callIdMdc` берёт на себя перенос MDC — то, что в Spring требовало ручной работы (неделя 11).

Метрики: `install(MicrometerMetrics) { registry = prometheusRegistry }` плюс отдельный маршрут `/metrics`.

---

## 4. Dependency injection вручную

```kotlin
fun Application.module() {
    val config = environment.config
    val dataSource = HikariDataSource(HikariConfig().apply {
        jdbcUrl = config.property("db.url").getString()
        username = config.property("db.user").getString()
        password = config.property("db.password").getString()
        maximumPoolSize = config.property("db.poolSize").getString().toInt()
    })

    val txRunner = TransactionRunner(dataSource)
    val accounts = AccountJdbcRepository(dataSource)
    val ledger = LedgerJdbcRepository(dataSource)
    val transfers = TransferService(txRunner, accounts, ledger)

    configureRouting(transfers)

    environment.monitor.subscribe(ApplicationStopping) { dataSource.close() }
}
```

Сравнение подходов:

| | Spring DI | Ручное wiring |
|---|---|---|
| Обнаружение ошибок | на старте контекста | **на компиляции** |
| Читаемость графа | нужен инструмент/дебаг | виден построчно |
| Объём кода | меньше | больше |
| Масштабирование | хорошо | нужен Koin при росте |
| «Магия» | есть | нет |

Обратите внимание на `ApplicationStopping` — graceful shutdown (неделя 13) здесь тоже пишется явно: закрыть пул, дождаться завершения задач.

---

## 5. Конфигурация

`application.conf` (HOCON):

```hocon
ktor {
    deployment { port = 8080, port = ${?PORT} }
    application { modules = [ study.week14.ApplicationKt.module ] }
}
db {
    url = "jdbc:postgresql://localhost:5432/fintech"
    url = ${?DB_URL}
    poolSize = 10
}
```

Синтаксис `${?ENV}` — переопределение из окружения. Секреты, как и на неделе 9, только из окружения.

Отличие от Spring: нет `@ConfigurationProperties` и relaxed binding — вы читаете конфигурацию явно и сами приводите типы. Проще отладить, легче забыть валидацию значения.

---

## 6. Тестирование

```kotlin
class PaymentRoutesTest {

    @Test
    fun `returns 404 for unknown payment`() = testApplication {
        application { testModule(service = fakeService) }
        val response = client.get("/api/payments/999") { bearerAuth(token) }
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `rejects request without token`() = testApplication {
        application { testModule(service = fakeService) }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/payments/1").status)
    }
}
```

`testApplication` поднимает приложение без сокета: настоящий routing, настоящие плагины, реальная сериализация. Ближайший аналог — `MockMvc`, но конфигурация теста — это тот же явный `module()`, что и в проде, только с подменёнными зависимостями (передаются параметром, без `@MockBean`).

Негативные security-тесты недели 9 переносятся один в один — и это хорошая проверка того, что вы перенесли контракт, а не код.

---

## 7. Что переносимо, а что нет

### 7.1 Переносимо полностью

- HTTP-семантика: методы, коды, заголовки, идемпотентность, кеширование.
- Дизайн API: ресурсы, DTO, error contract, пагинация, правила обратной совместимости.
- Всё, что касается данных: схема, constraints, индексы, планы, MVCC, уровни изоляции, блокировки, дедлоки, ledger, идемпотентность.
- Границы транзакций как **идея** (реализация другая).
- Безопасность: хеширование паролей, rotation, ownership, OWASP.
- Тестирование: Testcontainers, конкурентные и invariant-based тесты.
- Наблюдаемость: correlation id, метрики, четыре золотых сигнала.
- Docker, CI/CD, безопасные миграции.

### 7.2 Не переносимо

- Аннотации и автоконфигурация.
- Прокси-based `@Transactional` со всеми его ловушками (self-invocation, propagation).
- Устройство Spring Security filter chain.
- Spring Data репозитории и dirty checking.
- Модель «поток на запрос» (в Ktor — корутины).

### 7.3 Вывод

Соотношение примерно 80/20 в пользу переносимого. Это и есть содержательный итог недели: вы учили backend, а не Spring. Ревью с ментором проводится **без спора «какой фреймворк лучше»** — обсуждается, какие знания оказались фреймворко-независимыми.

---

## 8. Сравнительная таблица (заполняется по итогам)

| Критерий | Spring Boot | Ktor |
|---|---|---|
| Время старта | | |
| Строк кода на тот же API | | |
| Прозрачность execution flow | | |
| Явность зависимостей | | |
| Готовые интеграции | | |
| Модель конкурентности | поток на запрос | корутины |
| Порог входа для команды | | |
| Экосистема и найм | | |

Таблицу нужно заполнить измерениями, а не впечатлениями: замерить старт, посчитать строки, отметить, сколько времени заняло найти причину конкретной ошибки.

---

## 9. Лаборатория недели

1. Перенести `health`, аутентификацию и один payments-endpoint на Ktor.
2. Использовать **тот же** контракт API (можно ту же OpenAPI-спецификацию) и ту же схему PostgreSQL.
3. Реализовать единый error contract через `StatusPages` — формат ответа должен совпадать со Spring-версией байт в байт.
4. Настроить `CallId` + `CallLogging` и убедиться, что `requestId` возвращается и попадает в логи.
5. Перенести негативные security-тесты недели 9 и убедиться, что они проходят.
6. Замерить время старта обоих приложений и посчитать строки кода.
7. Заполнить сравнительную таблицу.
8. Отметить в отдельном документе, какие знания оказались полностью переносимыми.

---

## 10. Типичные ошибки недели

1. Блокирующий JDBC прямо в обработчике маршрута (без `withContext`) — занят поток event loop.
2. Разный формат ошибок в Spring- и Ktor-версиях.
3. Забытый `authenticate { }` вокруг части маршрутов.
4. Алгоритм подписи JWT берётся из токена, а не фиксируется.
5. `ignoreUnknownKeys = false` → падение на новом поле клиента.
6. Ручное wiring без закрытия ресурсов при остановке (`ApplicationStopping`).
7. Спор «Spring против Ktor» вместо анализа переносимости знаний.
8. Перенос кода вместо переноса контракта — в итоге два разных API.

---

## 11. Критерий готовности

- Можете реализовать endpoint без Spring-аннотаций.
- Транзакционная корректность не изменилась после смены фреймворка.
- Формат ошибок и контракт API идентичны в обеих реализациях.
- Можете назвать конкретный список знаний, которые полностью переносимы.

## 12. Официальные материалы

- Ktor Server Documentation — Creating a server, Routing, Plugins, StatusPages, Authentication, Testing.
- kotlinx.serialization — Basics, JSON configuration.
- Ktor — CallId, CallLogging, MicrometerMetrics.
- Kotlin Coroutines Guide — Dispatchers (для блокирующих вызовов).
