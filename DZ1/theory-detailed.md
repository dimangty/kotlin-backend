# DZ1 — Подробная теория

Разбор всех пунктов задания из [readme.md](readme.md).

---

## 1. Среда разработки: IDE и JDK

### 1.1 JDK — фундамент

Перед любой IDE нужен JDK (Java Development Kit). Kotlin компилируется в JVM-байткод, поэтому JDK обязателен.

- **JRE** — только среда выполнения (`java`).
- **JDK** — JRE + компилятор + инструменты разработки (`javac`, `jar`, `jstack`, ...).
- **Версии**: LTS-релизы — 8, 11, 17, 21, 25. Для новых проектов брать **21** (или 25, если экосистема готова).
- **Дистрибутивы**: Eclipse Temurin (самый нейтральный), Amazon Corretto, Azul Zulu, GraalVM (даёт native-image), Oracle JDK.
- **Управление версиями**: SDKMAN! (`sdk install java 21-tem`), jenv, или встроенный в IDEA загрузчик JDK.

Проверка установки:
```bash
java -version && javac -version
```

Переменная `JAVA_HOME` должна указывать на корень JDK — Gradle и Maven ориентируются на неё.

### 1.2 IntelliJ IDEA

Де-факто стандарт для Kotlin (Kotlin разрабатывает та же JetBrains).

- **Community Edition** — бесплатна, поддерживает Kotlin, Gradle, Maven, JUnit, Git, отладчик. Для Ktor и чистого Kotlin-бэкенда полностью достаточна.
- **Ultimate** — платная: Spring/Spring Boot support (навигация по бинам, автоконфигурация, endpoints), встроенный HTTP Client (`.http` файлы), Database tools, профилировщик, поддержка Docker/Kubernetes.

Ключевые вещи, которые стоит настроить сразу:
- `File → Project Structure → SDK` — выбрать JDK проекта.
- `Settings → Build Tools → Gradle → Gradle JVM` — та же версия JDK.
- Включить «Build and run using: Gradle» или «IntelliJ IDEA» — второй вариант быстрее для простых проектов.
- Горячие клавиши: `Shift+Shift` (поиск везде), `Ctrl/Cmd+B` (перейти к объявлению), `Alt+Enter` (быстрые исправления), `Ctrl/Cmd+Alt+L` (форматирование).

### 1.3 VS Code

Лёгкая альтернатива. Kotlin поддерживается через расширения (Kotlin Language Server, Kotlin Debug Adapter), но поддержка заметно слабее IDEA: хуже автодополнение по типам, медленнее анализ, отладка ограничена. Разумный сценарий: править конфиги, Dockerfile, фронтенд-часть, а Kotlin-код писать в IDEA.

### 1.4 OpenCode

Терминальный AI-агент/редактор, работающий поверх проекта. Полезен для рутинных правок и навигации без GUI, но не заменяет полноценный анализ кода и отладчик IDE.

---

## 2. Создание проекта: Ktor / Spring Boot × Gradle / Maven

### 2.1 Системы сборки

**Gradle**

Скрипт сборки — это код (Kotlin DSL `build.gradle.kts` или Groovy DSL `build.gradle`).

```kotlin
plugins {
    kotlin("jvm") version "2.1.0"
    application
}

repositories { mavenCentral() }

dependencies {
    implementation("io.ktor:ktor-server-core:3.0.3")
    testImplementation(kotlin("test"))
}

kotlin { jvmToolchain(21) }

application { mainClass.set("com.example.MainKt") }
```

Особенности:
- Инкрементальная сборка + build cache + Gradle Daemon → быстро на больших проектах.
- Kotlin DSL даёт автодополнение и типобезопасность в скрипте сборки.
- **Gradle Wrapper** (`gradlew`, `gradlew.bat`, `gradle/wrapper/`) фиксирует версию Gradle — коммитить в репозиторий обязательно, запускать через `./gradlew`, а не глобальный `gradle`.
- Конфигурации зависимостей: `implementation` (не протекает в API потребителей), `api` (протекает), `compileOnly`, `runtimeOnly`, `testImplementation`.

Полезные команды:
```bash
./gradlew build          # компиляция + тесты + сборка артефакта
./gradlew run            # запуск (плагин application)
./gradlew test
./gradlew dependencies   # дерево зависимостей
./gradlew clean
```

**Maven**

Декларативный XML (`pom.xml`), жёсткий жизненный цикл: `validate → compile → test → package → verify → install → deploy`.

```xml
<properties>
    <kotlin.version>2.1.0</kotlin.version>
    <maven.compiler.release>21</maven.compiler.release>
</properties>
<dependencies>
    <dependency>
        <groupId>org.jetbrains.kotlin</groupId>
        <artifactId>kotlin-stdlib</artifactId>
        <version>${kotlin.version}</version>
    </dependency>
</dependencies>
```

Особенности: предсказуемость, огромная экосистема плагинов, `mvn dependency:tree`, но многословность и отсутствие настоящей инкрементальности. Есть свой wrapper — `./mvnw`.

**Что выбрать**: для Kotlin — Gradle с Kotlin DSL (нативная поддержка, меньше boilerplate). Maven — если так принято в команде/компании.

### 2.2 Ktor

Асинхронный фреймворк от JetBrains, построенный на корутинах. Минималистичный: всё подключается плагинами.

Генерация: **https://start.ktor.io** — выбираем имя, Gradle Kotlin DSL, engine (Netty/CIO/Jetty), плагины (Routing, Content Negotiation, CORS, Auth, Call Logging).

Минимальное приложение:

```kotlin
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun main() {
    embeddedServer(Netty, port = 8080) {
        routing {
            get("/health") { call.respondText("OK") }
            get("/users/{id}") {
                val id = call.parameters["id"]
                call.respondText("user $id")
            }
        }
    }.start(wait = true)
}
```

Ключевые понятия:
- **Application** — корневой объект, куда «устанавливаются» плагины.
- **Plugin / install** — перехватчики пайплайна: `install(ContentNegotiation) { json() }`, `install(CORS)`, `install(CallLogging)`.
- **Pipeline** — цепочка фаз обработки запроса; плагины вклиниваются в нужную фазу.
- **Routing** — DSL маршрутизации, вложенные `route { }`.
- Всё suspend-friendly: обработчики — это корутины, блокирующие вызовы внутри них вредны.

Плюсы: лёгкий, быстрый старт, идиоматичный Kotlin, мало «магии».
Минусы: меньше готовых интеграций, больше писать руками (DI, транзакции, валидация).

### 2.3 Spring Boot

Тяжеловесный, но максимально «батарейки в комплекте».

Генерация: **https://start.spring.io** — Project: Gradle-Kotlin, Language: Kotlin, Spring Boot 3.x, Dependencies: Spring Web, Spring Data JPA, Validation, Actuator.

```kotlin
@SpringBootApplication
class DemoApplication

fun main(args: Array<String>) {
    runApplication<DemoApplication>(*args)
}

@RestController
@RequestMapping("/users")
class UserController(private val service: UserService) {

    @GetMapping("/{id}")
    fun get(@PathVariable id: Long): UserDto = service.find(id)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@RequestBody @Valid body: CreateUserRequest): UserDto = service.create(body)
}
```

Ключевые понятия:
- **IoC-контейнер и DI**: бины создаёт Spring, зависимости внедряются через конструктор.
- **Автоконфигурация**: `@SpringBootApplication` включает `@EnableAutoConfiguration` — Spring смотрит на classpath и настраивает то, что нашёл (есть `spring-boot-starter-webmvc` → поднимет встроенный Tomcat).
- **Стартеры**: `spring-boot-starter-*` — наборы согласованных по версиям зависимостей.
- **Профили**: `application.yml` + `application-dev.yml`, активация `--spring.profiles.active=dev`.
- **Actuator**: `/actuator/health`, `/actuator/metrics` из коробки.
- Spring MVC (блокирующий, сервлеты) vs Spring WebFlux (реактивный, Reactor); в Kotlin WebFlux удобно использовать с корутинами.

Для Kotlin обязательны плагины `kotlin-spring` (делает классы `open`) и `kotlin-jpa` (генерирует no-arg конструкторы для сущностей) — Spring Initializr добавляет их сам.

Плюсы: экосистема, безопасность, данные, транзакции, тестирование — всё готово.
Минусы: медленный старт, больше памяти, много «магии», сложнее отлаживать автоконфигурацию.

### 2.4 Сравнение

| | Ktor | Spring Boot |
|---|---|---|
| Модель | корутины, неблокирующая | сервлеты (MVC) или реактор (WebFlux) |
| Конфигурация | явный код (`install`) | автоконфигурация, аннотации |
| Стартап | ~0.3–1 с | ~2–5 с |
| DI | нет (или Koin/Kodein) | встроенный |
| Порог входа | ниже | выше |
| Когда брать | микросервисы, API-шлюзы, лёгкие сервисы | корпоративные приложения, богатая доменная логика |

---

## 3. HTTP

### 3.1 Модель

HTTP — текстовый (до 1.1) прикладной протокол «запрос–ответ» поверх TCP. **Stateless**: сервер по умолчанию не хранит состояние между запросами; состояние — в куках, токенах, БД.

Структура запроса:
```http
POST /api/orders HTTP/1.1
Host: example.com
Content-Type: application/json
Authorization: Bearer eyJ...
Content-Length: 27

{"item":"book","qty":2}
```

Структура ответа:
```http
HTTP/1.1 201 Created
Content-Type: application/json
Location: /api/orders/42
Cache-Control: no-store

{"id":42,"status":"NEW"}
```

### 3.2 Методы и их свойства

| Метод | Safe | Idempotent | Тело запроса | Назначение |
|---|---|---|---|---|
| GET | да | да | нет | получить ресурс |
| HEAD | да | да | нет | заголовки без тела |
| OPTIONS | да | да | нет | возможности/preflight |
| PUT | нет | **да** | да | полная замена/создание по известному URI |
| DELETE | нет | **да** | нет | удалить |
| POST | нет | **нет** | да | создать/выполнить действие |
| PATCH | нет | нет* | да | частичное изменение |

- **Safe** — не изменяет состояние сервера (можно кешировать, префетчить, повторять).
- **Idempotent** — N одинаковых запросов дают то же состояние, что и один. Важно: речь о *состоянии сервера*, а не о теле ответа (второй DELETE вернёт 404, но состояние то же).
- \* PATCH может быть идемпотентным, если описан как замена конкретных полей, и не будет — если это операция вида «увеличить на 1».

### 3.3 Коды состояния

- **2xx**: 200 OK, 201 Created (+`Location`), 202 Accepted (асинхронно), 204 No Content.
- **3xx**: 301 Moved Permanently, 302 Found, 304 Not Modified (условный GET, тела нет), 307/308 — редирект с сохранением метода и тела.
- **4xx**: 400 Bad Request, 401 Unauthorized (не аутентифицирован), 403 Forbidden (аутентифицирован, но нет прав), 404 Not Found, 405 Method Not Allowed, 409 Conflict (конфликт состояния, например дубликат), 410 Gone, 415 Unsupported Media Type, 422 Unprocessable Entity (валидация), 428 Precondition Required, 429 Too Many Requests (+`Retry-After`).
- **5xx**: 500 Internal Server Error, 502 Bad Gateway, 503 Service Unavailable, 504 Gateway Timeout.

Правило: 4xx — «виноват клиент, повтор без изменений не поможет»; 5xx — «виноват сервер, повтор может помочь».

### 3.4 Важные заголовки

- Контент: `Content-Type`, `Content-Length`, `Accept`, `Content-Encoding` (gzip/br), `Accept-Encoding`.
- Кеш: `Cache-Control`, `ETag`, `If-None-Match`, `Last-Modified`, `If-Modified-Since`, `Vary`, `Age`.
- Аутентификация: `Authorization`, `WWW-Authenticate`, `Cookie`, `Set-Cookie` (с флагами `HttpOnly`, `Secure`, `SameSite`).
- Прокси/трассировка: `X-Forwarded-For`, `X-Forwarded-Proto`, `Forwarded`, `traceparent`.
- Условные запросы: `If-Match` (оптимистичная блокировка при обновлении), `If-Unmodified-Since`.
- Безопасность: `Strict-Transport-Security`, `Content-Security-Policy`, `X-Content-Type-Options: nosniff`.

### 3.5 Версии протокола

- **HTTP/1.0** — по соединению на запрос.
- **HTTP/1.1** — keep-alive, chunked transfer, `Host` (виртуальные хосты), pipelining (на практике не используется). Проблема **head-of-line blocking**: ответы идут строго по порядку.
- **HTTP/2** — бинарный фрейминг, мультиплексирование потоков в одном TCP-соединении, HPACK-сжатие заголовков, server push (сейчас практически заброшен). HOL-блокировка осталась на уровне TCP.
- **HTTP/3** — поверх QUIC (UDP): независимые потоки без TCP-HOL, встроенный TLS 1.3, быстрое установление соединения (0-RTT), миграция соединения при смене сети.

### 3.6 REST в двух словах

Ресурсы идентифицируются URI, действия выражаются методами, ответы — представлениями (JSON). Признаки хорошего API: существительные во множественном числе в путях (`/orders/42/items`), корректные коды, версионирование (`/v1/...`), пагинация (`?page=&size=` или курсорная), единый формат ошибок (например RFC 7807 `application/problem+json`).

---

## 4. CORS (Cross-Origin Resource Sharing)

### 4.1 Откуда проблема

Браузер применяет **Same-Origin Policy**: скрипт со страницы `https://app.example.com` не может *прочитать* ответ от `https://api.example.com`, потому что origin отличается. Origin = схема + хост + порт. `http://a.com` ≠ `https://a.com` ≠ `https://a.com:8443` ≠ `https://b.a.com`.

Это защита пользователя: иначе вредоносный сайт мог бы читать вашу почту, пользуясь вашими куками.

CORS — механизм, которым **сервер** явно разрешает браузеру отдать ответ чужому origin.

Важно понимать: CORS не защищает сервер. Запрос (в простых случаях) до сервера доходит и выполняется; браузер лишь не отдаёт ответ JS-коду. Curl, Postman и мобильные клиенты CORS игнорируют.

### 4.2 Простой запрос

Считается «простым», если метод GET/HEAD/POST, а заголовки — только из белого списка (`Accept`, `Accept-Language`, `Content-Language`, `Content-Type` со значением `application/x-www-form-urlencoded`, `multipart/form-data` или `text/plain`).

```http
GET /api/data HTTP/1.1
Origin: https://app.example.com
```
```http
HTTP/1.1 200 OK
Access-Control-Allow-Origin: https://app.example.com
```

Если заголовка `Access-Control-Allow-Origin` нет или он не совпадает — браузер выбрасывает ошибку CORS, хотя запрос выполнился.

### 4.3 Preflight

Любой `Content-Type: application/json`, метод `PUT`/`DELETE`/`PATCH` или кастомный заголовок (`Authorization` в fetch, `X-Request-Id`) делают запрос «сложным». Браузер сначала шлёт:

```http
OPTIONS /api/orders HTTP/1.1
Origin: https://app.example.com
Access-Control-Request-Method: POST
Access-Control-Request-Headers: content-type, authorization
```

Сервер должен ответить:
```http
HTTP/1.1 204 No Content
Access-Control-Allow-Origin: https://app.example.com
Access-Control-Allow-Methods: POST, PUT, DELETE
Access-Control-Allow-Headers: Content-Type, Authorization
Access-Control-Max-Age: 86400
```

`Access-Control-Max-Age` кеширует результат preflight в браузере (браузеры ограничивают: Chrome — до 2 часов, Firefox — до 24).

### 4.4 Куки и креденшелы

Чтобы браузер отправил куки/`Authorization` и дал прочитать ответ:
- клиент: `fetch(url, { credentials: 'include' })`;
- сервер: `Access-Control-Allow-Credentials: true` **и** `Access-Control-Allow-Origin` с конкретным origin (`*` запрещён вместе с credentials);
- куки должны быть `SameSite=None; Secure`.

### 4.5 Чтение заголовков ответа

JS по умолчанию видит только «безопасные» заголовки. Чтобы отдать свои:
```http
Access-Control-Expose-Headers: X-Total-Count, Location
```

### 4.6 Практика

Обязательно добавлять `Vary: Origin`, если список разрешённых origin динамический — иначе промежуточный кеш отдаст ответ с чужим `Access-Control-Allow-Origin`.

**Ktor:**
```kotlin
install(CORS) {
    allowHost("app.example.com", schemes = listOf("https"))
    allowMethod(HttpMethod.Put)
    allowMethod(HttpMethod.Delete)
    allowHeader(HttpHeaders.ContentType)
    allowHeader(HttpHeaders.Authorization)
    exposeHeader("X-Total-Count")
    allowCredentials = true
    maxAgeInSeconds = 86400
}
```

**Spring Boot:**
```kotlin
@Configuration
class CorsConfig : WebMvcConfigurer {
    override fun addCorsMappings(registry: CorsRegistry) {
        registry.addMapping("/api/**")
            .allowedOrigins("https://app.example.com")
            .allowedMethods("GET", "POST", "PUT", "DELETE")
            .allowedHeaders("Content-Type", "Authorization")
            .allowCredentials(true)
            .maxAge(3600)
    }
}
```
(При использовании Spring Security CORS настраивается ещё и в цепочке фильтров: `http.cors { }`.)

Альтернатива CORS — проксирование: фронтенд и API живут на одном origin, nginx/API-gateway пробрасывает `/api` на бэкенд. Тогда CORS вообще не нужен.

---

## 5. Идемпотентность

### 5.1 Определение

Операция идемпотентна, если её многократное выполнение приводит к тому же состоянию системы, что и однократное. Математически: `f(f(x)) = f(x)`.

Примеры:
- `SET balance = 100` — идемпотентно.
- `balance = balance - 100` — нет.
- `PUT /users/42 {"name":"Ann"}` — идемпотентно.
- `POST /payments {"amount":100}` — нет: два вызова = два платежа.

### 5.2 Зачем это нужно

Классический сценарий распределённых систем:

1. Клиент отправляет `POST /payments`.
2. Сервер списывает деньги и падает / сеть рвётся до доставки ответа.
3. Клиент видит таймаут и **не знает**, прошла операция или нет.
4. Клиент ретраит → второе списание.

Сеть даёт гарантию максимум **at-least-once**; «exactly-once» на уровне доставки недостижим, но достижим на уровне **эффекта** — за счёт идемпотентности получателя. То же касается очередей (Kafka, RabbitMQ), вебхуков платёжных систем, ретраев в load balancer.

### 5.3 Idempotency-Key

Промышленный стандарт (Stripe, PayPal, Adyen; черновик RFC про заголовок `Idempotency-Key`).

```http
POST /payments HTTP/1.1
Idempotency-Key: 7b1c1e9c-0c4b-4d3f-9c0a-3f2f5a9d1a11
Content-Type: application/json

{"amount": 100, "currency": "EUR"}
```

Алгоритм на сервере:

1. Прочитать ключ. Нет ключа → 400 (если ключ обязателен).
2. Атомарно попытаться вставить запись `(key, request_hash, status=IN_PROGRESS)` — уникальный индекс по `key`.
3. **Вставка удалась** → выполнить бизнес-операцию, сохранить результат (код ответа + тело) в ту же запись, `status=DONE`, вернуть результат.
4. **Конфликт вставки**:
   - `status=DONE` → сравнить `request_hash`; совпадает → вернуть сохранённый ответ; не совпадает → `422`/`409` («тот же ключ с другим телом»).
   - `status=IN_PROGRESS` → `409 Conflict` или `425 Too Early`, клиент повторит позже.
5. Фоновый процесс чистит записи старше TTL (обычно 24 ч).

Критично: запись результата и сама бизнес-операция должны быть в **одной транзакции** (или через transactional outbox), иначе возможен разрыв.

### 5.4 Другие приёмы

- **Естественный ключ**: `PUT /orders/{clientGeneratedUuid}` — клиент сам генерирует ID, повтор просто перезапишет то же.
- **Уникальный бизнес-констрейнт**: уникальный индекс на `(user_id, external_order_id)`; дубликат ловится БД.
- **Дедупликация по окну**: хранить хэши запросов за последние N минут (годится не для денег).
- **Условные запросы**: `If-Match: "etag"` — оптимистичная блокировка, повторный апдейт со старым ETag получит `412 Precondition Failed`.
- **Машина состояний**: `NEW → PAID → SHIPPED`; переход из `PAID` в `PAID` — no-op, а не ошибка.
- **Consumer-side dedup в очередях**: таблица обработанных `message_id`.

### 5.5 Ретраи

Идемпотентность обязана идти в паре с грамотными ретраями:
- ретраить только идемпотентные операции (или операции с ключом идемпотентности);
- exponential backoff + **jitter**, иначе ретраи всех клиентов синхронизируются и добьют сервис;
- ограничить число попыток, уважать `Retry-After`;
- **circuit breaker** — перестать долбить упавший сервис (Resilience4j).

---

## 6. Кеширование: in-memory и standalone

### 6.1 Зачем

Кеш меняет память на скорость: хранит результат дорогой операции (запрос в БД, вызов внешнего API, тяжёлый расчёт), чтобы не выполнять её повторно. Ключевая метрика — **hit ratio** (доля попаданий). Кеш с hit ratio 20% часто вреден: добавляет сложность и рассогласование, почти не давая выигрыша.

Порядок задержек:
```
L1 CPU cache      ~1 нс
RAM (in-memory)   ~100 нс
Redis по сети     ~0.2–1 мс
SSD               ~100 мкс
Запрос в БД       ~1–10 мс
Внешний HTTP API  ~50–500 мс
```

### 6.2 In-memory (локальный, embedded)

Кеш живёт в куче того же JVM-процесса.

Реализации:
- `HashMap`/`ConcurrentHashMap` — только для очень простых случаев: нет вытеснения, нет TTL, риск утечки памяти.
- **Caffeine** — де-факто стандарт на JVM: TTL, размерное вытеснение, алгоритм W-TinyLFU, асинхронная загрузка, статистика.
- Guava Cache — предшественник Caffeine.
- Ehcache — умеет ещё и диск/кластер.

```kotlin
val cache: LoadingCache<Long, User> = Caffeine.newBuilder()
    .maximumSize(10_000)
    .expireAfterWrite(Duration.ofMinutes(5))
    .recordStats()
    .build { id -> userRepository.findById(id) }

val user = cache.get(userId)
```

Плюсы: минимальная задержка, нет сериализации и сетевых сбоев, просто внедрить.
Минусы:
- у каждой ноды свой кеш → рассинхрон между инстансами (пользователь видит то старые, то новые данные в зависимости от того, куда попал запрос);
- дублирование памяти × число нод;
- всё теряется при рестарте/деплое → «холодный старт» и всплеск нагрузки на БД;
- увеличивает heap → давление на GC, риск OOM;
- инвалидировать во всех нодах сразу нечем (нужен pub/sub).

### 6.3 Standalone (распределённый, remote)

Кеш — отдельный сетевой сервис.

- **Redis** — самый популярный: структуры данных (string, hash, list, set, sorted set, stream), TTL, персистентность (RDB/AOF), репликация, Cluster (шардирование), Pub/Sub, Lua-скрипты (атомарность), распределённые блокировки.
- **Memcached** — проще и легче, только key-value, многопоточный, без персистентности.
- **Hazelcast / Apache Ignite** — распределённые in-memory data grid, могут работать и embedded, и standalone.

Плюсы: общий для всех инстансов (консистентная картина), переживает рестарт приложения, масштабируется независимо, не ест heap приложения, можно использовать для rate limiting, сессий, блокировок.
Минусы: сетевой round-trip, стоимость сериализации, новая точка отказа и новый компонент в эксплуатации, вопросы безопасности (пароль, TLS, сеть).

### 6.4 Многоуровневый кеш

На практике часто комбинируют: **L1 (Caffeine, TTL 10–60 с) → L2 (Redis, TTL минуты/часы) → БД**. L1 гасит горячие ключи и снимает нагрузку с Redis, L2 обеспечивает общее состояние. Рассогласование L1 лечится коротким TTL или инвалидацией через Redis Pub/Sub.

### 6.5 Стратегии работы

- **Cache-aside (lazy loading)** — самая распространённая: приложение само читает кеш, при промахе идёт в БД и кладёт в кеш. Просто, кеш может «отвалиться» без потери корректности.
- **Read-through** — кеш сам умеет загружать (Caffeine `LoadingCache`).
- **Write-through** — запись идёт в кеш и синхронно в БД: кеш всегда актуален, запись медленнее.
- **Write-behind (write-back)** — запись в кеш, в БД асинхронно пачкой: быстро, но риск потери данных.
- **Refresh-ahead** — обновление горячих ключей до истечения TTL, чтобы не было промахов.

### 6.6 Вытеснение и TTL

Алгоритмы: **LRU** (давно не использовался), **LFU** (редко используется), **FIFO**, **W-TinyLFU** (Caffeine — комбинирует частоту и свежесть, обычно лучший hit ratio), Random.

TTL-политики: `expireAfterWrite` (фиксированное время жизни), `expireAfterAccess` (продлевается при обращении), `refreshAfterWrite` (перезагрузка в фоне).

### 6.7 Типичные проблемы

- **Инвалидация** — «одна из двух сложных задач в CS». Варианты: короткий TTL (просто, но данные подтухают), явное удаление ключа при изменении (точно, но легко забыть путь изменения), версионирование ключей (`user:42:v7`), событийная инвалидация через Pub/Sub/Kafka.
- **Stale data** — кеш отдаёт устаревшее. Решается TTL и явной инвалидацией; иногда допустимо осознанно (eventual consistency).
- **Cache stampede / thundering herd** — популярный ключ протух, сотни запросов одновременно идут в БД. Лечится блокировкой на загрузку (single-flight), jitter в TTL, refresh-ahead, отдачей stale-while-revalidate.
- **Cache penetration** — запросы к несуществующим ключам всегда бьют в БД. Лечится кешированием «пустого» результата с коротким TTL или Bloom-фильтром.
- **Горячий ключ** — один ключ создаёт всю нагрузку; лечится локальным L1-кешем поверх Redis.
- **Что нельзя кешировать**: персональные данные без учёта пользователя в ключе, результаты, зависящие от прав доступа (иначе утечка между пользователями), данные, требующие строгой консистентности (баланс перед списанием).

### 6.8 HTTP-кеширование

Отдельный уровень — между клиентом, CDN и сервером.

```http
Cache-Control: public, max-age=300, stale-while-revalidate=60
ETag: "a1b2c3"
```

- `no-store` — не хранить вообще (для персональных/чувствительных данных);
- `no-cache` — хранить, но перепроверять перед использованием;
- `private` — только браузер, не CDN; `public` — можно промежуточным кешам;
- `max-age` / `s-maxage` (для shared-кешей);
- **условный GET**: клиент шлёт `If-None-Match: "a1b2c3"`, сервер при совпадении отвечает `304 Not Modified` без тела — экономия трафика;
- `Vary: Accept-Encoding, Origin` — по каким заголовкам кеш должен различать варианты ответа.

**Spring:** `@Cacheable`, `@CacheEvict`, `@CachePut` + `CacheManager` (Caffeine или Redis) — абстракция позволяет менять реализацию, не трогая код.
**Ktor:** `install(CachingHeaders)` для HTTP-заголовков; прикладной кеш — вручную через Caffeine или Lettuce/Jedis.

---

## 7. Инструменты JDK

Официальный обзор: https://docs.oracle.com/javase/8/docs/technotes/tools/ (для современных JDK — раздел «Tool Specifications» в документации соответствующей версии).

### 7.1 Основные

| Утилита | Что делает |
|---|---|
| `java` | запуск класса/jar; с JDK 11 умеет запускать одиночный `.java`-файл |
| `javac` | компилятор Java |
| `jar` | создание/просмотр архивов (`jar tf app.jar`) |
| `javadoc` | генерация документации |
| `javap` | дизассемблер: `javap -c -p MyClass` покажет байткод — очень полезно, чтобы понять, во что компилируется Kotlin |
| `jshell` | REPL, с JDK 9 |
| `jlink` | сборка урезанного рантайма из модулей |
| `jpackage` | нативные инсталляторы (JDK 14+) |
| `jdeps` | анализ зависимостей классов/пакетов |
| `keytool` | keystore, сертификаты, самоподписанный TLS для локальной разработки |

### 7.2 Диагностика и мониторинг

| Утилита | Что делает |
|---|---|
| `jps` | список работающих JVM и их PID: `jps -lvm` |
| `jinfo` | конфигурация и системные свойства JVM |
| `jstack <pid>` | дамп всех потоков: ищем дедлоки (`Found one Java-level deadlock`), зависшие потоки, состояния BLOCKED/WAITING |
| `jmap <pid>` | `jmap -histo` — гистограмма объектов; `jmap -dump:live,format=b,file=heap.hprof <pid>` — дамп кучи (потом смотреть в Eclipse MAT / VisualVM) |
| `jstat` | статистика в реальном времени: `jstat -gcutil <pid> 1000` — заполнение поколений, число и время GC |
| `jcmd` | «швейцарский нож»: `jcmd <pid> help`, `Thread.print`, `GC.heap_info`, `GC.heap_dump`, `VM.flags`, `JFR.start` — рекомендуемая замена jstack/jmap/jinfo |
| `jconsole` | GUI: heap, потоки, классы, MBeans |
| `jvisualvm` | GUI-профилировщик (сейчас отдельная загрузка), CPU/memory sampling, анализ дампов |
| `jfr` | работа с записями Java Flight Recorder |
| `jdb` | консольный отладчик поверх JDI |

### 7.3 Практический сценарий разбора инцидента

```bash
jps -l                                   # найти PID сервиса
jstat -gcutil <pid> 1000 10              # растёт ли Old Gen, часты ли Full GC
jcmd <pid> Thread.print > threads.txt    # что делают потоки
jcmd <pid> GC.heap_dump /tmp/heap.hprof  # дамп для анализа утечки
jcmd <pid> JFR.start duration=60s filename=/tmp/rec.jfr  # профилирование
```

Типовые находки: пул потоков исчерпан блокирующими вызовами; утечка через статическую коллекцию/кеш без вытеснения; постоянные Full GC из-за слишком маленькой кучи.

### 7.4 Полезные флаги JVM

```
-Xms512m -Xmx512m              # фиксировать размер кучи (в контейнере — одинаковые значения)
-XX:MaxRAMPercentage=75.0      # вместо -Xmx в Docker/Kubernetes
-XX:+UseG1GC                   # G1 по умолчанию; ZGC/Shenandoah — для низких пауз
-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/dumps
-XX:+ExitOnOutOfMemoryError    # пусть оркестратор перезапустит
-Dfile.encoding=UTF-8
```

---

## 8. Отладка консольного приложения

### 8.1 Приложение

```kotlin
fun main() {
    val parts = listOf("Hello", "world")
    val message = buildMessage(parts)
    println(message)
}

fun buildMessage(parts: List<String>): String {
    val sb = StringBuilder()
    for ((index, part) in parts.withIndex()) {
        sb.append(part)
        if (index < parts.lastIndex) sb.append(", ")
    }
    return "$sb!"
}
```

### 8.2 Базовый цикл

1. **Брейкпоинт** — клик по «жёлобу» слева от номера строки (или `Ctrl/Cmd+F8`). Появляется красный кружок.
2. **Запуск в Debug** — `Shift+F9`, либо зелёный треугольник у `fun main` → «Debug».
3. Выполнение останавливается **до** выполнения строки с брейкпоинтом.
4. Панель Debug:
   - **Frames** — стек вызовов; кликая по кадрам, видно переменные каждого уровня.
   - **Variables** — значения в текущей области; объекты раскрываются в дерево.
   - **Watches** — произвольные выражения, вычисляемые на каждом шаге.
   - **Console** — вывод программы.

### 8.3 Шаги

| Действие | Клавиша | Смысл |
|---|---|---|
| Step Over | `F8` | выполнить строку целиком, не заходя внутрь вызовов |
| Step Into | `F7` | зайти внутрь вызываемой функции |
| Force Step Into | `Alt+Shift+F7` | зайти даже в библиотечный/синтетический код |
| Step Out | `Shift+F8` | доработать текущую функцию и вернуться к вызывающей |
| Run to Cursor | `Alt+F9` | выполнить до строки под курсором |
| Resume | `F9` | продолжить до следующего брейкпоинта |
| Evaluate Expression | `Alt+F8` | вычислить произвольный код в текущем контексте |

### 8.4 Продвинутые приёмы

- **Условный брейкпоинт**: ПКМ по брейкпоинту → `Condition: index == 1`. Незаменимо в циклах и при обработке одного «плохого» элемента.
- **Логирующий брейкпоинт**: снять галку `Suspend`, включить `Evaluate and log` — печатает значение, не останавливая программу (замена `println`, не требующая правки кода).
- **Exception breakpoint**: `Run → View Breakpoints → + → Java Exception Breakpoint`, указать, например, `NullPointerException` — остановка ровно в момент броска, ещё до раскрутки стека.
- **Field watchpoint**: остановка при чтении/записи поля.
- **Drop Frame**: «откатить» выполнение к началу текущего метода и пройти его заново (побочные эффекты не откатываются).
- **Set Value**: в Variables ПКМ → `Set Value` — подменить значение переменной на лету и проверить другую ветку.
- **Mute Breakpoints**: временно отключить все брейкпоинты.
- **Smart Step Into** (`Shift+F7`): выбрать, в какой именно вызов заходить в строке `a(b(c()))`.

### 8.5 Отладка Kotlin: нюансы

- В корутинах стек «рваный»; включите вкладку **Coroutines** в панели Debug (нужна `kotlinx-coroutines-debug` / флаг `-Dkotlinx.coroutines.debug`) и опцию «Async stack traces».
- Лямбды и `inline`-функции компилируются в байткод так, что шаги могут выглядеть неожиданно; `Force Step Into` помогает.
- Свойства с кастомными геттерами: обращение в Variables вызовет геттер (возможны побочные эффекты).

### 8.6 Remote debug

Для отладки приложения, запущенного вне IDE (в Docker, на стенде):

```bash
java -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005 -jar app.jar
```

В IDEA: `Run → Edit Configurations → + → Remote JVM Debug`, host/port, затем подключиться. `suspend=y` заставит JVM ждать подключения отладчика — удобно для отладки старта приложения.

Для Gradle: `./gradlew run --debug-jvm` (по умолчанию порт 5005, ждёт подключения).

Важно: не оставлять JDWP-порт открытым в проде — это удалённое выполнение кода без аутентификации.

### 8.7 Когда отладчик не помогает

- Гонки и плавающие баги: брейкпоинт меняет тайминг и прячет проблему → логи, трейсинг, стресс-тесты.
- Прод: вместо отладчика — структурированные логи с correlation-id, метрики (Micrometer/Prometheus), распределённая трассировка (OpenTelemetry), Java Flight Recorder.
- Иногда быстрее всего — написать падающий unit-тест, воспроизводящий баг, и отлаживать уже его.
