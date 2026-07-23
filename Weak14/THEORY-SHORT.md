# Неделя 14 — краткая теория

**Тема:** Ktor — явный серверный стек для сравнения.
**Результат:** реализовать тот же API в Ktor и увидеть, что меняется фреймворк, а HTTP/SQL/транзакции остаются.

---

## 1. Модель Ktor

- **Нет component scan, нет автоконфигурации.** Приложение — функция, которая явно устанавливает плагины и объявляет маршруты.
- **Плагин** (`install(...)`) — перехватчик в конвейере (pipeline) обработки запроса. Всё, что Spring делает через автоконфигурацию, здесь пишется руками.
- Асинхронность **встроена**: обработчики — `suspend`-функции, под капотом корутины и неблокирующий сервер (Netty/CIO), а не «поток на запрос».

```kotlin
fun main() = embeddedServer(Netty, port = 8080) { module() }.start(wait = true)

fun Application.module() {
    install(ContentNegotiation) { json() }
    install(StatusPages) { /* единый error contract */ }
    install(Authentication) { jwt { /* ... */ } }
    routing { healthRoutes(); paymentRoutes(service) }
}
```

## 2. Routing

```kotlin
fun Route.paymentRoutes(service: PaymentService) {
    route("/api/payments") {
        get("/{id}") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, error("BAD_ID"))
            val payment = service.find(id) ?: return@get call.respond(HttpStatusCode.NotFound, ...)
            call.respond(payment.toResponse())
        }
        authenticate("jwt") {
            post { val req = call.receive<CreatePaymentRequest>(); ... }
        }
    }
}
```

Маршруты — дерево; авторизация и другие плагины применяются к поддереву. Всё видно в одном месте, без «где-то есть аннотация».

## 3. Соответствие Spring → Ktor

| Spring | Ktor |
|---|---|
| `@RestController` + `@GetMapping` | `routing { get("/path") { ... } }` |
| Jackson auto-config | `install(ContentNegotiation) { json() }` (kotlinx.serialization) |
| `@ControllerAdvice` | `install(StatusPages) { exception<T> { ... } }` |
| `@Valid` + Bean Validation | `install(RequestValidation)` или ручная проверка |
| Spring Security filter chain | `install(Authentication) { jwt { } }` + `authenticate { }` |
| DI-контейнер | конструкторы вручную или Koin |
| `@Transactional` | явная функция-обёртка `transaction { }` |
| `application.yaml` | `application.conf` (HOCON) / `ApplicationConfig` |

## 4. Явное wiring

```kotlin
fun Application.module() {
    val dataSource = HikariDataSource(hikariConfig(environment.config))
    val repo = PaymentJdbcRepository(dataSource)
    val service = PaymentService(repo)
    routing { paymentRoutes(service) }
}
```

Плюсы: граф зависимостей виден и проверяется компилятором; нет «магии» и сюрпризов на старте. Минусы: больше кода, при росте проекта нужен DI-фреймворк (Koin).

## 5. Тестирование

```kotlin
@Test
fun health() = testApplication {
    application { module() }
    val response = client.get("/health")
    assertEquals(HttpStatusCode.OK, response.status)
}
```

`testApplication` поднимает приложение без реального сокета — аналог `MockMvc`, но с настоящим маршрутизатором и плагинами.

## 6. Что переносимо, а что нет

**Переносимо полностью:** HTTP-семантика, дизайн API, DTO и валидация как идея, SQL, индексы, транзакции, уровни изоляции, блокировки, идемпотентность, ledger, тесты на Testcontainers.

**Не переносимо:** аннотации, автоконфигурация, прокси-based транзакции, устройство filter chain.

Соотношение примерно 80/20 в пользу переносимого — и это главный вывод недели.

---

## Контрольные вопросы

1. **В чём принципиальная разница подходов?** Spring — конвенции и автоконфигурация (меньше кода, больше неявного); Ktor — явная сборка (больше кода, весь execution flow виден).
2. **Почему в Ktor нет `@Transactional`?** Нет прокси-контейнера: транзакция оформляется явной функцией-обёрткой вокруг блока кода.
3. **Что меняется в корректности данных при переходе на Ktor?** Ничего: MVCC, изоляция, блокировки и constraints — свойства PostgreSQL, а не фреймворка.
4. **Чем модель исполнения Ktor отличается от Spring MVC?** Ktor неблокирующий и построен на корутинах; блокирующий JDBC-вызов нужно уводить на отдельный dispatcher.
