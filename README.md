# Kotlin Backend: 16-недельный практический трек

Учебный репозиторий для перехода от клиентской Kotlin-разработки к backend. Основной стек - Spring Boot, база данных - PostgreSQL; в конце тот же подход переносится на Ktor для осознанного сравнения фреймворков.

Главный акцент сделан не на количестве аннотаций, а на корректной работе с данными: SQL, индексах, планах выполнения, MVCC, транзакциях, блокировках, идемпотентности и проверке денежных инвариантов под конкурентной нагрузкой.

> Это набор независимых недельных лабораторий, а не одно монолитное приложение. Каждый каталог можно собирать, запускать и изучать отдельно.

## Что будет изучено

- жизненный цикл HTTP-запроса и устройство Spring Boot-приложения;
- REST, DTO, validation, error contracts и разделение слоёв;
- PostgreSQL на уровне heap pages, tuples, MVCC, WAL, VACUUM и statistics;
- B-tree, составные, covering, partial, expression, GIN и BRIN индексы;
- `EXPLAIN (ANALYZE, BUFFERS)`, selectivity и стоимость запросов;
- ACID, уровни изоляции, lost update, serialization failure, locks и deadlocks;
- финтех-инварианты: ledger, idempotency key и безопасный перевод средств;
- Spring Data JPA, JDBC, Flyway и осознанные transaction boundaries;
- authentication, authorization, ownership и negative security cases;
- Testcontainers, конкурентные и invariant-based тесты;
- корутины, timeout, cancellation, retry и внешние сервисы;
- structured logging, metrics, Actuator, Micrometer и Prometheus;
- Docker, CI/CD, health/readiness и безопасные миграции;
- перенос HTTP, SQL и транзакций со Spring на Ktor;
- финальный вертикальный fintech-сценарий.

## Технологии

- Kotlin 2.3.21, JDK 17 и Gradle Kotlin DSL;
- Spring Boot 4.1.0, Spring MVC, Spring Security, JDBC и JPA;
- Ktor 3.5.1 и Kotlin Coroutines;
- PostgreSQL 17, Flyway и HikariCP;
- JUnit Platform и Testcontainers 2.0.5;
- Docker, Docker Compose, Actuator, Micrometer и Prometheus.

## Карта курса

| Неделя | Тема | Практический результат | Проект |
|---:|---|---|---|
| 1 | Kotlin/JVM, Gradle и HTTP | `health`, `hello`, `echo`; путь запроса от HTTP до controller | [Weak1](Weak1/) |
| 2 | Spring MVC, REST, DTO и validation | CRUD заметок, слои, единый error contract и optimistic version | [Weak2](Weak2/) |
| 3 | Схема, SQL и физическое хранение | Финтех-схема, constraints, 100 000 строк, `ctid` и `xmin` | [SQL-first](Weak3/) / [Spring + JDBC](Weak3-1/) |
| 4 | B-tree изнутри | Миллион строк, UUID/bigint/timestamp/status индексы и размеры relations | [SQL-first](Weak4/) / [Spring API](Weak4-1/) |
| 5 | Составные и специальные индексы | Covering/partial/expression/GIN/BRIN и план истории платежей | [SQL-first](Weak5/) / [Spring API](Weak5-1/) |
| 6 | ACID, MVCC и isolation levels | Аномалии двух сессий, два безопасных списания и Serializable retry | [SQL-first](Weak6/) / [Spring service](Weak6-1/) |
| 7 | Locks, deadlocks и fintech correctness | Перевод с ordered locks, ledger и idempotency key | [Weak7](Weak7/) |
| 8 | Spring Data, JDBC/JPA и migrations | Flyway-схема, JPA dirty checking и JDBC projection | [Weak8](Weak8/) |
| 9 | Authentication и API security | Password hashing, token rotation, RBAC/ownership и 401/403 tests | [Weak9](Weak9/) |
| 10 | Тестирование базы и конкурентности | PostgreSQL Testcontainers, constraints и parallel debit tests | [Weak10](Weak10/) |
| 11 | Coroutines и resilience | Timeout, cancellation, idempotent retry и reconciliation | [Weak11](Weak11/) |
| 12 | Logs, metrics и diagnostics | Request/operation IDs, безопасный MDC и Prometheus metrics | [Weak12](Weak12/) |
| 13 | Docker, CI/CD и safe deploy | Multi-stage image, non-root runtime, health и readiness | [Weak13](Weak13/) |
| 14 | Ktor для сравнения | Routing, plugins, auth и tests без Spring annotations | [Weak14](Weak14/) |
| 15 | Ktor + PostgreSQL | Hikari, Flyway, JDBC transactions, ledger и Testcontainers | [Weak15](Weak15/) |
| 16 | Финальный fintech slice | Idempotent transfer, audit, cursor ledger и concurrent processing | [Weak16](Weak16/) |

## Углублённые треки

Две темы плана проходят сквозь несколько недель сразу и вынесены в отдельные документы. Это чек-листы: они связывают обязательные лабораторные работы с конкретными скриптами репозитория.

- [Индексы](docs/index-track.md) - дополнение к неделям 3-5: семь обязательных лабораторных, форма ответа и типовые ошибки.
- [Транзакции и конкурентность](docs/concurrency-track.md) - дополнение к неделям 6-7, 10 и 11: каталог проблем, семь обязательных экспериментов и классификация retryable ошибок.
- [Безопасный деплой](Weak13/docs/safe-deploy.md) - дополнение к неделям 8 и 13: expand-contract, поведение при двух instance, откат и restore.

## SQL-лаборатории

Помимо Kotlin-проектов в репозитории лежат скрипты, которые запускаются напрямую в `psql` и воспроизводят учебный эффект целиком. Все они рассчитаны на `ON_ERROR_STOP=1` и проверены на PostgreSQL 17.

| Файл | Неделя | Что показывает |
|---|---|---|
| `Weak3/sql/queries.sql`, `Weak3/ledger.sql` | 3 | 20 запросов по пяти блокам, `ctid`/`xmin`, ledger против projection, dead tuples и `VACUUM` |
| `Weak4/lab.sql`, `Weak4/write-overhead.sql` | 4 | B-tree на миллионе строк; цена записи при 0/1/4 индексах |
| `Weak5/lab.sql`, `Weak5/special-indexes.sql` | 5 | порядок колонок, covering/partial, отказ planner от индекса, expression/GIN/BRIN |
| `Weak6/session-{a,b}.sql` | 6 | аномалии изоляции в двух сессиях |
| `Weak7/locks-*.sql` | 7 | lock wait, deadlock, единый lock order, `SKIP LOCKED`, разбор через `pg_locks` |
| `Weak8/migration-lab.sql` | 8 | что переписывает таблицу, `NOT VALID` + `VALIDATE` против прямого `SET NOT NULL`, бэкофилл батчами |
| `Weak12/slow-query-lab.sql` | 12 | `pg_stat_statements`, поиск и починка медленного запроса, «плохой план против lock wait» |

Каталоги называются `Weak`, а не `Week`, по исторической причине. Это имя сохранено, чтобы не ломать существующие ссылки и команды. Варианты `Weak3-1`...`Weak6-1` дополняют SQL-first лаборатории полноценными Spring Boot-приложениями.

## Структура репозитория

```text
BackEnd/
├── Weak1 ... Weak16       # основная последовательность курса
├── Weak3-1 ... Weak6-1   # расширенные Spring/JDBC-версии SQL-недель
├── docs/                  # сквозные треки по индексам и конкурентности
├── AUDIT.md               # результаты полной проверки проектов
└── README.md
```

Внутри проекта обычно находятся:

- `README.md` - цель, запуск и задания недели;
- `REVIEW.md` - результат code review и оставшаяся учебная работа;
- `src/main` и `src/test` - приложение и тесты;
- `build.gradle.kts` и Gradle Wrapper - воспроизводимая сборка;
- `compose.yaml` - PostgreSQL или полный runtime, если он нужен неделе;
- `db/migration`, `lab.sql` или session scripts - миграции и SQL-эксперименты.

Каждый недельный `README.md` построен одинаково: результат недели, таблица «теория - где она в коде», команды запуска, задания, что разобрать с ментором, критерий готовности, контрольные вопросы и официальные материалы. Порядок разделов повторяет структуру плана, поэтому неделю плана и каталог репозитория можно читать рядом.

## Требования

- JDK 17;
- Docker Engine или Docker Desktop с Compose;
- Unix-подобная shell для команд из примеров.

Устанавливать Gradle отдельно не нужно: каждый Kotlin-проект содержит `./gradlew`.

Если порт PostgreSQL на машине уже занят другой базой, любой недельный compose принимает переменную:

```bash
PG_PORT=55432 docker compose up -d
```

Тесты на Testcontainers порт не публикуют и от этой переменной не зависят.

## Быстрый старт

Первое Spring Boot-приложение:

```bash
cd Weak1
./gradlew test
./gradlew bootRun
```

Проект с PostgreSQL:

```bash
cd Weak7
docker compose up -d
./gradlew test
./gradlew bootRun
```

Ktor-приложение:

```bash
cd Weak14
./gradlew test
./gradlew run
```

Точные команды, API endpoints, порты и задания указаны в `README.md` конкретной недели.

## Как проходить курс

1. Начните с результата недели и критериев готовности, а не с копирования кода.
2. Запустите проект и тесты с чистого окружения.
3. Для недель 3-6 сначала выполните SQL-first лабораторию, затем изучите Spring-вариант `-1`.
4. Воспроизведите проблему: плохой plan, lost update, deadlock, duplicate request или timeout.
5. Исправьте её и подтвердите результат SQL-планом, constraint или конкурентным тестом.
6. Прочитайте `REVIEW.md`, ответьте на контрольные вопросы и оформите собственные выводы.

### Принципы трека

- Не переходить к ORM, пока ключевые запросы не написаны вручную.
- Не добавлять индекс без запроса, данных, статистики и плана выполнения.
- Сначала воспроизводить конкурентную проблему двумя SQL-сессиями, затем исправлять приложение.
- Дублировать критичные инварианты на уровне PostgreSQL через constraints и транзакции.
- Проверять PostgreSQL на PostgreSQL: H2 не заменяет MVCC, locks и planner реальной базы.
- Повторять всю бизнес-транзакцию только для классифицированных retryable errors.

## Проверка проектов

Для запуска всех Gradle-тестов из корня репозитория нужен работающий Docker:

```bash
for project in Weak1 Weak2 Weak{3,4,5,6}-1 Weak{7..16}; do
  (cd "$project" && ./gradlew test) || exit 1
done
```

Интеграционные тесты DB-недель используют настоящий PostgreSQL 17 через Testcontainers; остальные тесты проверяют HTTP contracts, security cases, cancellation и конкурентные инварианты. Результаты полного аудита находятся в [AUDIT.md](AUDIT.md), а детальные замечания - в `REVIEW.md` каждой недели.

## Границы курса

Kafka, сложные микросервисы, distributed transactions, Kubernetes, sharding и distributed locks намеренно оставлены за рамками трека. Сначала репозиторий доводит до уверенного владения одним сервисом, одной PostgreSQL и корректным вертикальным финтех-сценарием.
