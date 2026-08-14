# DBAppWeb

Учебное Web- и Desktop-приложение по материалам `konspekt-s-rekomendaciyami-po-resheniyu-problem (2).pdf` из корня репозитория. Compose Multiplatform переиспользует интерфейс между Kotlin/Wasm и JVM Desktop, Spring Boot 4.1.0 выполняет реальные JDBC-сценарии, PostgreSQL 18 хранит только изолированную схему `dbapp_lab`.

На стартовом экране находятся шесть разделов:

- ACID - 3 примера;
- Аномалии - 5 примеров;
- Уровни изоляции - 9 примеров;
- Блокировки - 8 примеров;
- Дедлоки - 3 примера;
- Индексы - 16 примеров.

Каждый из 44 примеров запускается двумя параллельными HTTP-запросами и выводит журнал клиентского fan-out, серверного барьера, фактический SQL, результаты, границы транзакций, ожидаемые SQLSTATE и планы `EXPLAIN (ANALYZE, BUFFERS)`.

## Быстрый запуск

Нужен запущенный Docker Desktop. Из папки `DBAppWeb` поднимите PostgreSQL 18 и бэкенд:

```bash
docker compose up -d --build
docker compose ps
```

Порты привязаны только к loopback-интерфейсу:

- Spring Boot API: `http://localhost:18082`;
- PostgreSQL контейнера: `localhost:5433`;
- установленный через Homebrew PostgreSQL продолжает работать на `localhost:5432`.

### Web-клиент

Запустите браузерный клиент:

```bash
./gradlew wasmJsBrowserDevelopmentRun --max-workers=1
```

Откройте [http://localhost:8081](http://localhost:8081). Клиент автоматически проверит REST API и покажет версию PostgreSQL.

### Desktop-клиент

Запустите JVM Desktop-приложение с тем же интерфейсом и сценариями:

```bash
./gradlew run --max-workers=1
```

Готовое приложение для текущей операционной системы создаётся командой:

```bash
./gradlew createDistributable --max-workers=1
```

Результат находится в `build/compose/binaries/main/app`. На macOS команда `./gradlew packageDmg` создаёт `build/compose/binaries/main/dmg/DBAppWeb-1.0.0.dmg`.

По умолчанию Desktop обращается к `http://127.0.0.1:18082`. Другой адрес можно задать перед запуском:

```bash
export DBAPPWEB_API_URL='http://127.0.0.1:18082'
./gradlew run --max-workers=1
```

Остановка без удаления данных:

```bash
docker compose down
```

Команда `docker compose down -v` дополнительно безвозвратно удаляет учебный volume PostgreSQL; используйте её только для полного сброса стенда.

## Запуск бэкенда с Homebrew PostgreSQL 18

Docker Compose использует отдельный контейнер, поэтому не требует изменения локальных ролей и `pg_hba.conf`. Для разработки Spring Boot можно вместо него использовать установленный Homebrew PostgreSQL:

```bash
cd Backend
export SERVER_PORT=18082
export DEMO_DATABASE_URL='jdbc:postgresql://localhost:5432/postgres'
export DEMO_DATABASE_USER="$USER"
export DEMO_DATABASE_PASSWORD=''
./gradlew bootRun
```

Бэкенд создаёт только схему `dbapp_lab`. Не направляйте учебное приложение на production-базу.

## Проверки

Клиентские тесты и production bundle:

```bash
./gradlew allTests wasmJsBrowserDistribution --max-workers=1
./gradlew desktopTest createDistributable --max-workers=1
```

Сборка и тесты Spring Boot:

```bash
cd Backend
./gradlew test bootJar
```

Полный прогон всех 44 сценариев на контейнерной БД:

```bash
./gradlew test --rerun-tasks \
  -Ddbappweb.integration=true \
  -Ddbappweb.url=jdbc:postgresql://localhost:5433/dbappweb \
  -Ddbappweb.user=dbapp \
  -Ddbappweb.password=dbapp
```

## REST API

- `GET /actuator/health` - Docker healthcheck;
- `GET /api/status` - настоящее JDBC-соединение и версия сервера;
- `GET /api/catalog` - шесть тем и 44 примера;
- `POST /api/examples/{id}/parallel-runs/{runId}/participants/{participant}?participantCount=2` - один участник параллельного запуска.

После нажатия кнопки клиент создаёт 128-битный `runId` и одновременно отправляет участников `0` и `1`. Spring Boot регистрирует оба запроса, открывает барьер только после их встречи и атомарно выбирает одного исполнителя JDBC-сценария. Второй запрос ждёт общий результат. Оба ответа содержат HTTP-журнал, полный SQL-лог возвращает только исполнитель, а клиент объединяет ответы без дублирования SQL.

Старого последовательного endpoint `/api/examples/{id}/run` нет. Отдельные учебные запуски дополнительно сериализуются через `ReentrantLock`, чтобы вкладки не портили общее подготовленное состояние. CORS разрешает только локальный Web-клиент на порту `8081`; Desktop не ограничивается браузерной CORS-моделью.

Для ручной проверки откройте два терминала и почти одновременно выполните запросы с одним идентификатором:

```bash
curl -X POST 'http://127.0.0.1:18082/api/examples/acid-error/parallel-runs/0123456789abcdef0123456789abcdef/participants/0?participantCount=2'
curl -X POST 'http://127.0.0.1:18082/api/examples/acid-error/parallel-runs/0123456789abcdef0123456789abcdef/participants/1?participantCount=2'
```

Первый запрос ожидает второй не более десяти секунд. В логах обоих ответов видны регистрация и открытие барьера.

## Структура

- `src/commonMain` - модели, каталог, общий Ktor REST-контракт и адаптивный Compose UI;
- `src/wasmJsMain` - браузерная точка входа и Ktor Fetch-клиент;
- `src/desktopMain` - JVM-точка входа, окно Compose Desktop и Ktor CIO-клиент;
- `Backend/src/main` - Spring MVC API и JDBC-сценарии;
- `Backend/src/test` - контракт реестра и опциональный интеграционный прогон;
- `compose.yaml` - PostgreSQL 18 и Spring Boot;
- `REVIEW.md` - результаты финального ревью.

Прямой JDBC выбран намеренно: ORM скрыл бы snapshots, две независимые сессии, row locks и SQLSTATE, которые являются предметом лаборатории.
