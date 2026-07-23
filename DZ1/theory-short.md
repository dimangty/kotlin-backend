# DZ1 — Краткая теория (шпаргалка)

## 1. IDE

| Инструмент | Зачем |
|---|---|
| IntelliJ IDEA (Community/Ultimate) | Основная IDE для Kotlin/Java. Community хватает для Kotlin + Gradle; Ultimate нужен для полноценной поддержки Spring, HTTP-клиента, БД |
| VS Code | Лёгкий редактор, Kotlin через extension (LSP), удобен для правок и не-JVM частей |
| OpenCode | CLI/агентный редактор, работает поверх проекта в терминале |

Ставить JDK 21 (LTS) — например Temurin/Corretto. Проверка: `java -version`, `javac -version`.

## 2. Создание проекта

**Ktor** — лёгкий асинхронный фреймворк на корутинах.
- Генератор: https://start.ktor.io (выбрать Gradle Kotlin DSL + нужные плагины)
- В IDEA: `New Project → Ktor`

**Spring Boot** — большой фреймворк с DI, стартерами, автоконфигурацией.
- Генератор: https://start.spring.io (Gradle/Maven, Language: Kotlin)
- В IDEA Ultimate: `New Project → Spring Boot`

**Gradle vs Maven**
- Gradle: `build.gradle.kts`, Kotlin DSL, инкрементальная сборка, быстрее, гибче.
- Maven: `pom.xml`, XML, жёсткий жизненный цикл, предсказуемее.
- Запуск: `./gradlew bootRun` / `./gradlew run` или `./mvnw spring-boot:run`.

## 3. HTTP

- Модель: запрос → ответ, stateless.
- Методы: `GET` (чтение), `POST` (создание), `PUT` (полная замена), `PATCH` (частичное изменение), `DELETE`, `HEAD`, `OPTIONS`.
- Свойства: **safe** (не меняет состояние: GET, HEAD, OPTIONS), **idempotent** (повтор даёт тот же результат: GET, PUT, DELETE, HEAD, OPTIONS).
- Коды: 1xx инфо, 2xx успех (200, 201, 204), 3xx редирект (301, 302, 304), 4xx ошибка клиента (400, 401, 403, 404, 409, 422, 429), 5xx ошибка сервера (500, 502, 503).
- Версии: HTTP/1.1 (текст, keep-alive), HTTP/2 (бинарный, мультиплексирование), HTTP/3 (QUIC поверх UDP).

## 4. CORS

Браузерная политика **Same-Origin Policy** запрещает JS читать ответ с другого origin (схема + хост + порт). CORS — способ сервера разрешить это.

- Простой запрос → браузер шлёт `Origin`, сервер отвечает `Access-Control-Allow-Origin`.
- Сложный запрос (нестандартный метод/заголовок) → **preflight** `OPTIONS` с `Access-Control-Request-Method/Headers`, сервер отвечает `Access-Control-Allow-Methods/Headers/Max-Age`.
- Куки/креды: клиент `credentials: 'include'`, сервер `Access-Control-Allow-Credentials: true` и **конкретный** origin (не `*`).
- CORS — не защита сервера: запрос доходит, браузер лишь скрывает ответ.

Ktor: `install(CORS) { allowHost(...); allowMethod(...) }`
Spring: `@CrossOrigin` или `WebMvcConfigurer.addCorsMappings`.

## 5. Идемпотентность

Повторный вызов не меняет результат по сравнению с одним вызовом.

- Идемпотентны: GET, PUT, DELETE. Не идемпотентен: POST.
- Проблема: клиент не получил ответ (таймаут) → ретрай → двойное списание.
- Решение: **Idempotency-Key** — клиент шлёт UUID в заголовке; сервер сохраняет `key → результат` и при повторе отдаёт сохранённый ответ.
- Нужно: TTL ключа, уникальный индекс в БД, обработка «запрос в процессе» (409/425).

## 6. Кеширование

**In-memory (локальный)** — кеш внутри процесса: `Map`, Caffeine, Guava.
- \+ наносекунды, нет сети. − у каждой ноды свой кеш, теряется при рестарте, дублирует память.

**Standalone (распределённый)** — отдельный сервис: Redis, Memcached, Hazelcast.
- \+ общий для всех нод, переживает рестарт, масштабируется. − сетевая задержка, сериализация, ещё одна точка отказа.

Стратегии: cache-aside (самая частая), read-through, write-through, write-behind.
Вытеснение: LRU, LFU, FIFO, TTL.
Проблемы: инвалидация, stale data, cache stampede (лечится блокировкой/jitter в TTL).

**HTTP-кеш**: `Cache-Control: max-age / no-store / private`, `ETag` + `If-None-Match` → `304 Not Modified`, `Last-Modified` + `If-Modified-Since`.

## 7. Инструменты JDK

| Утилита | Назначение |
|---|---|
| `java` | запуск приложения |
| `javac` | компиляция |
| `jar` | упаковка в архив |
| `javap` | дизассемблер class-файлов |
| `jshell` | REPL (с JDK 9) |
| `jps` | список JVM-процессов |
| `jstack` | дамп потоков (диагностика дедлоков) |
| `jmap` | дамп кучи / статистика памяти |
| `jstat` | статистика GC в реальном времени |
| `jcmd` | универсальная команда диагностики |
| `jconsole` / `jvisualvm` | GUI-мониторинг |
| `jdb` | консольный отладчик |
| `keytool` | работа с сертификатами/keystore |

Ссылка: https://docs.oracle.com/javase/8/docs/technotes/tools/

## 8. Отладка

1. Поставить брейкпоинт (клик по полю слева от номера строки).
2. Запустить в режиме Debug (`Shift+F9` / иконка жука).
3. Шаги: **Step Over** `F8`, **Step Into** `F7`, **Step Out** `Shift+F8`, **Resume** `F9`.
4. Смотреть Variables, Watches, Frames; **Evaluate Expression** `Alt+F8`.
5. Полезное: conditional breakpoint (ПКМ по брейкпоинту), exception breakpoint, «Drop Frame».

Пример:
```kotlin
fun main() {
    val message = "Hello, world!"   // breakpoint здесь
    println(message)
}
```
