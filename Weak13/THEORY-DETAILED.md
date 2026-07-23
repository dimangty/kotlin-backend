# Неделя 13 — подробная теория

Docker, CI/CD и безопасный деплой миграций.

> Критерий недели прост и жесток: **проект запускается на чистой машине по README**, а миграция не требует длительной блокировки таблицы. Всё остальное — детали.

---

## 1. Образ приложения

### 1.1 Multi-stage сборка

```dockerfile
# ---- build ----
FROM gradle:8.12-jdk17 AS build
WORKDIR /src
# сначала только то, что меняется редко → кеш слоёв
COPY gradle gradle
COPY gradlew settings.gradle.kts build.gradle.kts ./
RUN ./gradlew --no-daemon dependencies || true
COPY src src
RUN ./gradlew --no-daemon bootJar -x test

# ---- runtime ----
FROM eclipse-temurin:17-jre-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=build /src/build/libs/*.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
```

Принципы:

1. **Инструменты сборки не попадают в runtime.** Gradle, исходники, тестовые зависимости остаются в первом слое.
2. **JRE вместо JDK** — меньше размер и меньше поверхность атаки.
3. **Non-root.** Контейнер, работающий от root, при пробое даёт больше возможностей. Это же требование в Kubernetes (`runAsNonRoot`).
4. **Порядок слоёв по частоте изменений.** Зависимости меняются редко, код — часто. Правильный порядок экономит минуты на каждой сборке.
5. **Фиксированные версии базовых образов** — `latest` делает сборку невоспроизводимой.
6. **`.dockerignore`**: `build/`, `.git/`, `.gradle/`, `*.env`, ключи. Иначе секреты попадают в контекст сборки и в образ.

Дополнительно: layered JAR Spring Boot (`bootJar` + `layertools`) разбивает приложение на слои (dependencies / snapshot-dependencies / application), что ещё лучше использует кеш при частых деплоях.

### 1.2 JVM в контейнере

- JVM с Java 10+ читает cgroup-лимиты. Используйте `-XX:MaxRAMPercentage=75`, а не `-Xmx512m`: при изменении лимита контейнера настройка подстроится.
- **RSS процесса ≠ heap.** Сверх heap: metaspace, стеки потоков (~1 МБ × число потоков — вспомните 200 worker-потоков Tomcat), direct byte buffers, код JIT, GC-структуры. Лимит контейнера должен быть заметно выше heap, иначе OOMKill без единого `OutOfMemoryError` в логах.
- Диагностика: `jvm.memory.*` метрики (неделя 12) и `-XX:+HeapDumpOnOutOfMemoryError` с монтированным томом.

### 1.3 Сигналы и PID 1

Оркестратор посылает `SIGTERM` и через grace period — `SIGKILL`. Если JVM запущена не как PID 1 (например, через шелл-обёртку), сигнал может не дойти. Используйте `ENTRYPOINT` в exec-форме (JSON-массив), как выше, либо `--init`.

---

## 2. Health, readiness, graceful shutdown

### 2.1 Две разные пробы

| | Liveness | Readiness |
|---|---|---|
| Вопрос | процесс жив? | можно слать трафик? |
| Провал | **перезапуск** | вывод из балансировки |
| Зависимость от БД | **нет** | да, разумно |

Ошибка «liveness проверяет базу» приводит к цикличным перезапускам при недоступности БД: сервис, который мог бы отдавать понятные `503`, вместо этого умирает по кругу.

```yaml
management:
  endpoint.health.probes.enabled: true
  health.livenessstate.enabled: true
  health.readinessstate.enabled: true
```

- `/actuator/health/liveness` — только внутреннее состояние процесса.
- `/actuator/health/readiness` — состояние зависимостей: пул соединений, применённые миграции.

Проверка readiness должна быть **дешёвой**: `SELECT 1`, а не пересчёт агрегатов.

### 2.2 Graceful shutdown

```yaml
server.shutdown: graceful
spring.lifecycle.timeout-per-shutdown-phase: 20s
```

Правильная последовательность:

1. Получен `SIGTERM`.
2. Readiness → `DOWN`; балансировщик перестаёт направлять новые запросы (нужна пауза — балансировщик узнаёт не мгновенно, отсюда `preStop` sleep в Kubernetes).
3. Текущие запросы дорабатываются в пределах таймаута.
4. Закрываются пул соединений, планировщики, потребители.
5. Процесс выходит.

Без graceful shutdown каждый деплой обрывает часть запросов — для финансовых операций это ровно тот сценарий «клиент не знает, выполнилось ли», ради которого делалась идемпотентность (неделя 7).

---

## 3. Docker Compose

```yaml
services:
  postgres:
    image: postgres:17-alpine
    environment:
      POSTGRES_DB: fintech
      POSTGRES_USER: ${DB_USER}
      POSTGRES_PASSWORD: ${DB_PASSWORD}
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U ${DB_USER} -d fintech"]
      interval: 5s
      timeout: 3s
      retries: 10
    volumes:
      - pgdata:/var/lib/postgresql/data

  app:
    build: .
    depends_on:
      postgres:
        condition: service_healthy
    environment:
      SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/fintech
      SPRING_DATASOURCE_USERNAME: ${DB_USER}
      SPRING_DATASOURCE_PASSWORD: ${DB_PASSWORD}
    ports: ["8080:8080"]

volumes:
  pgdata:
```

Существенное: `depends_on: condition: service_healthy` (иначе приложение стартует раньше базы и падает), именованный volume (иначе данные теряются), секреты из окружения, а не из файла в образе.

Compose — инструмент разработки и демо. Для прода те же принципы выражаются манифестами оркестратора.

---

## 4. CI/CD

### 4.1 Этапы

```
1. checkout
2. lint / detekt / ktlint
3. build (./gradlew build -x test)
4. unit tests
5. integration tests (Testcontainers, неделя 10)
6. migrations check: Flyway на пустой базе с нуля
7. security: зависимости (OWASP/дерево уязвимостей), сканер секретов, скан образа
8. docker build + push (тег = commit SHA)
9. deploy (staging → prod)
10. smoke tests на развёрнутом окружении
```

### 4.2 Требования

- **Воспроизводимость.** Gradle wrapper, зафиксированные версии зависимостей и образов, `--no-daemon` в CI.
- **Красные тесты блокируют деплой.** Без исключений, иначе смысла нет.
- **Тег образа = commit SHA**, а не `latest`: иначе непонятно, что развёрнуто, и откат недетерминирован.
- **Секреты** — только из хранилища секретов CI, не в переменных репозитория с логированием.
- **Кеширование** Gradle и Docker-слоёв — иначе пайплайн станет слишком медленным, и его начнут обходить.

### 4.3 Testcontainers в CI

Нужен доступный Docker-демон. Полезно: singleton-контейнер, `fsync=off` для тестового PostgreSQL, `postgres:17-alpine`. Версия образа обязана совпадать с продовой.

### 4.4 Проверка миграций

Два независимых сценария:

1. **С нуля:** пустая база → все миграции → успех. Ловит несогласованные миграции.
2. **Поверх предыдущего релиза:** дамп схемы предыдущей версии → новые миграции → успех. Ловит несовместимые изменения.

---

## 5. Безопасные миграции

### 5.1 Почему backward-compatible

Во время выкатки одновременно работают инстансы старой и новой версии (rolling update). Схема после миграции обязана быть работоспособна для **обеих**. Отсюда — expand-contract.

### 5.2 Добавление обязательной колонки: три релиза

**Релиз 1 — expand**

```sql
SET lock_timeout = '3s';
ALTER TABLE payments ADD COLUMN external_ref text;   -- nullable, без DEFAULT
```

Старый код колонку не замечает. Новый код уже умеет с ней работать.

**Релиз 2 — migrate**

Новый код пишет `external_ref` для всех новых строк. Фоновая задача заполняет старые **батчами**:

```sql
UPDATE payments SET external_ref = ...
WHERE external_ref IS NULL AND id IN (
    SELECT id FROM payments WHERE external_ref IS NULL ORDER BY id LIMIT 5000
);
```

Один `UPDATE` на миллион строк — это долгая транзакция, лавина WAL, bloat и удержание блокировок. Батчи с коммитами между ними — обязательны.

**Релиз 3 — contract**

```sql
ALTER TABLE payments ADD CONSTRAINT payments_external_ref_nn
    CHECK (external_ref IS NOT NULL) NOT VALID;      -- мгновенно, без сканирования
ALTER TABLE payments VALIDATE CONSTRAINT payments_external_ref_nn;  -- сканирует, но слабая блокировка
ALTER TABLE payments ALTER COLUMN external_ref SET NOT NULL;        -- в PG 12+ использует проверенный CHECK
```

### 5.3 Таблица опасных операций

| Операция | Проблема | Безопасный вариант |
|---|---|---|
| `SET NOT NULL` | полное сканирование под `ACCESS EXCLUSIVE` | `CHECK ... NOT VALID` → `VALIDATE` → `SET NOT NULL` |
| `ADD FOREIGN KEY` | сканирование обеих таблиц | `ADD ... NOT VALID` → `VALIDATE CONSTRAINT` |
| `CREATE INDEX` | `SHARE`, блокирует запись | `CREATE INDEX CONCURRENTLY` (вне транзакции) |
| `DROP INDEX` | `ACCESS EXCLUSIVE` | `DROP INDEX CONCURRENTLY` |
| Смена типа колонки | перезапись таблицы | новая колонка + перелив + переключение |
| `RENAME COLUMN` | ломает работающий код | добавить новую, писать в обе, удалить старую |
| `UPDATE` всей таблицы | долгая транзакция, WAL, bloat | батчи |
| `ADD COLUMN ... DEFAULT` | в старых версиях — перезапись | в PG 11+ дёшево; проверьте версию |
| `VACUUM FULL` | `ACCESS EXCLUSIVE` на всё время | `pg_repack` или окно обслуживания |

### 5.4 Очередь блокировок — главная ловушка

`ALTER TABLE`, ожидающий блокировку, **сам блокирует** все последующие запросы к таблице — включая обычные `SELECT`, которые с ним не конфликтуют. Один `ALTER`, наткнувшийся на долгую транзакцию, останавливает сервис при полностью работоспособной базе.

Поэтому в каждой миграции:

```sql
SET lock_timeout = '3s';
```

и повтор миграции при неудаче. Лучше не применить миграцию сейчас, чем положить сервис.

### 5.5 CONCURRENTLY и Flyway

`CREATE INDEX CONCURRENTLY` не выполняется внутри транзакции — миграция должна быть помечена как нетранзакционная. При сбое остаётся невалидный индекс:

```sql
SELECT indexrelid::regclass FROM pg_index WHERE NOT indisvalid;
```

Такой индекс нужно удалить и создать заново.

### 5.6 Два инстанса стартуют одновременно

Flyway берёт блокировку на таблице `flyway_schema_history` — второй инстанс дождётся и увидит применённые миграции. Это решает проблему гонки миграций, но **не** решает проблему совместимости: пока первый инстанс мигрирует, второй (старой версии) продолжает работать с изменённой схемой. Единственная защита — backward-compatible миграции.

Альтернатива для крупных систем — вынести миграции из старта приложения в отдельный шаг пайплайна (init-контейнер/job). Тогда контроль над моментом применения явный.

---

## 6. Backup, restore, rollback

### 6.1 Откат кода и откат схемы — разные вещи

Откатить образ на предыдущий тег просто. Откатить схему — почти никогда: down-миграция, удаляющая колонку, уничтожает данные, записанные после выката. Поэтому стратегия такова:

- миграции только совместимые → откат кода безопасен без отката схемы;
- разрушающие изменения (`DROP COLUMN`) выполняются **после** того, как новый код отработал достаточно долго.

### 6.2 Backup

| Тип | Инструмент | Восстановление |
|---|---|---|
| Логический | `pg_dump` / `pg_dumpall` | восстановление в новую базу, медленно на больших объёмах |
| Физический + WAL | `pg_basebackup` + архив WAL | PITR — восстановление на любой момент времени |

**Бэкап без проверенного восстановления бэкапом не является.** Регулярная проверка restore — часть работы, а не опция. Полезная практика — восстанавливать прод-дамп на staging и прогонять на нём миграции: это и проверка бэкапа, и проверка миграций на реальных данных.

### 6.3 Что разобрать с ментором

- Что произойдёт при одновременном старте двух инстансов.
- Процедура отката: шаги, ответственные, время.
- Проверка восстановления базы из бэкапа — не в теории, а выполненная.

---

## 7. Деплой демо

Минимум для этой недели: HTTPS (сертификат от Let's Encrypt через reverse proxy), переменные окружения для секретов, health-проверки, отдельная база, отключённый доступ к Actuator извне.

Smoke tests после деплоя (неделя 10): `/health`, `/actuator/health/readiness`, версия приложения, один читающий и один пишущий сценарий.

---

## 8. Лаборатория недели

1. Multi-stage Dockerfile с non-root и JRE; замерить размер образа до и после оптимизации.
2. `docker compose up -d` поднимает весь проект с нуля на чистой машине по README.
3. Настроить graceful shutdown и проверить: во время выключения ни один запрос не оборван.
4. Разделить liveness и readiness; показать, что при остановленной БД readiness падает, а процесс не перезапускается.
5. Pipeline: тесты на Testcontainers + проверка миграций на пустой базе + сборка образа с тегом SHA.
6. Сделать безопасную миграцию новой обязательной колонки в три релиза; на каждом шаге показать взятые блокировки через `pg_locks`.
7. Смоделировать ловушку очереди блокировок: долгая транзакция + `ALTER TABLE` + обычный `SELECT`. Показать, что `SELECT` встал. Повторить с `lock_timeout`.
8. Задеплоить demo с HTTPS и прогнать smoke tests.
9. Восстановить базу из бэкапа и убедиться, что данные на месте.

---

## 9. Типичные ошибки недели

1. Образ на базе JDK и с root-пользователем.
2. `latest` как тег базового образа и как тег своего образа.
3. `-Xmx` с абсолютным числом при изменяемом лимите контейнера.
4. Liveness, проверяющий базу → цикл перезапусков.
5. Нет graceful shutdown — деплой рвёт запросы.
6. Миграция, несовместимая со старой версией кода.
7. `SET NOT NULL` на большой таблице в рабочее время.
8. `CREATE INDEX` без `CONCURRENTLY`.
9. Миграция без `lock_timeout`.
10. Разовый `UPDATE` на миллион строк.
11. Секреты в образе или в `docker-compose.yml` в репозитории.
12. Бэкапы, восстановление которых никто не проверял.
13. Actuator, доступный из интернета.

---

## 10. Критерий готовности

- Проект запускается на чистой машине по README.
- Миграция не требует недопустимой длительной блокировки таблицы, и вы можете показать, какие блокировки она берёт.
- Пайплайн запускает Testcontainers и проверку миграций; красные тесты блокируют деплой.
- Понимаете и можете объяснить, что произойдёт при двух одновременно стартующих инстансах.
- Процедура rollback и восстановления базы проверена, а не описана.

## 11. Официальные материалы

- Docker — Multi-stage builds, Best practices for writing Dockerfiles, Compose file reference.
- Spring Boot Reference — Container Images, Graceful Shutdown, Kubernetes Probes.
- Flyway — Migrations, Concurrent migrations, Baseline.
- PostgreSQL: `ALTER TABLE` (locking notes), `CREATE INDEX CONCURRENTLY`, Chapter 26 — Backup and Restore, Chapter 27 — Continuous Archiving and PITR.
