# Неделя 13. Docker, CI/CD и безопасный деплой миграций

**Результат недели:** собирать, тестировать и разворачивать приложение воспроизводимо - и уметь откатиться.

Собрать image умеют все. Неделя про другое: что произойдёт в момент выкатки, когда старая и новая версии приложения работают одновременно на одной схеме базы, и что делать, когда выкатка оказалась неудачной.

## Теория и где она в проекте

| Тема | Где смотреть |
|---|---|
| Multi-stage build | [Dockerfile](Dockerfile) - Gradle и JDK остаются в build-стадии, в runtime едет только JRE и JAR |
| Воспроизводимость | сборка идёт через wrapper проекта, а не через версию Gradle с хоста |
| Минимальный контекст сборки | [.dockerignore](.dockerignore) - контекст 2.3 KB вместо всего каталога с `build/` и кэшами |
| Non-root runtime | пользователь `app` создаётся в image, `USER app` до `ENTRYPOINT` |
| Read-only filesystem | [compose.yaml](compose.yaml) - `read_only: true` плюс `tmpfs: /tmp` |
| Readiness против liveness | `management.endpoint.health.probes.enabled` в [application.yaml](src/main/resources/application.yaml), проверка в [ReadinessTest.kt](src/test/kotlin/study/week13/ReadinessTest.kt) |
| Graceful shutdown | `server.shutdown: graceful` и `spring.lifecycle.timeout-per-shutdown-phase: 20s`; `stop_grace_period: 25s` в compose даёт контейнеру дожить до конца |
| CI: тесты до image | [.github/workflows/week13-ci.yml](../.github/workflows/week13-ci.yml), `working-directory: Weak13` |

Пара «graceful shutdown + readiness» - это одно решение, а не два. При выкатке instance сначала должен перестать отвечать `UP` на readiness (балансировщик уводит трафик), и только потом закрывать соединения. Иначе часть запросов получит `502` при каждом деплое.

## Запуск

```bash
docker compose up --build     # APP_PORT=18080 docker compose up --build, если 8080 занят
curl -s localhost:8080/actuator/health/readiness
docker compose exec app id    # uid=... app: контейнер не работает от root
```

```bash
./gradlew test                # readiness-тест без Docker
```

## Безопасный деплой

Процедура выкатки, отката и восстановления вынесена в отдельный документ: **[docs/safe-deploy.md](docs/safe-deploy.md)**. Там - expand-contract в три релиза, поведение при двух одновременно стартующих instance, правила отката и процедура restore. Замеры, на которые опирается документ, делаются в [Weak8/migration-lab.sql](../Weak8/migration-lab.sql): прямой `SET NOT NULL` держит ACCESS EXCLUSIVE всё время скана таблицы, безопасная последовательность - доли миллисекунды.

## Задания

1. **PostgreSQL в compose.** Добавить базу и `depends_on` с healthcheck, чтобы `docker compose up` поднимал весь проект одной командой. Проверить на чистой машине по своему README.
2. **Проверка миграций в CI.** Отдельный шаг pipeline: прогнать миграции на пустой базе и провалить сборку, если они не применяются или не идемпотентны при повторном старте.
3. **Expand-contract в три релиза.** Выполнить сценарий из `docs/safe-deploy.md` на схеме недели 8: релиз N добавляет nullable-колонку, N+1 бэкофиллит и включает `NOT NULL` через `NOT VALID` + `VALIDATE`, N+2 убирает старое поле. Зафиксировать время удержания ACCESS EXCLUSIVE на каждом шаге.
4. **Репетиция отката.** Выкатить версию, откатиться на предыдущий tag, убедиться, что схема при этом не менялась и сервис работает. Процедура, которую ни разу не выполняли, не существует.
5. **Backup и restore.** Снять `pg_dump -Fc`, восстановить в отдельную базу, сверить `count(*)` и контрольные суммы, записать время восстановления.
6. **Два instance одновременно.** Запустить два контейнера приложения на одной базе и проверить: миграции применяются один раз, второй instance ждёт, readiness не отвечает `UP` до окончания миграций.
7. **Ужесточить runtime.** Добавить `no-new-privileges`, ограничения памяти и CPU, pinned digest вместо тега. Объяснить, что из этого защищает от чего.

## Что разобрать с ментором

- Что произойдёт при двух одновременно стартующих instance и почему это не гипотетический вопрос.
- Процедура отката и восстановления базы - по вашей собственной репетиции.
- Что должно попасть в CI обязательно, а что делает pipeline медленным без пользы.

## Критерий готовности

- Проект запускается на чистой машине по README, без знаний из головы.
- Миграция не требует недопустимо долгой блокировки таблицы - и это подтверждено замером.
- Откат приложения не требует отката схемы.

## Контрольные вопросы

- Чем readiness отличается от liveness и что случится, если перепутать их местами?
- Почему multi-stage build - это не только про размер image?
- Почему контейнер не должен работать от root, если он и так изолирован?
- Почему схема обязана быть совместима сразу с двумя версиями приложения?
- Когда откат схемы допустим, а когда он уже уничтожение данных?

## Материалы

- [Spring Boot: Container Images](https://docs.spring.io/spring-boot/reference/packaging/container-images/index.html)
- [Spring Boot: Kubernetes Probes](https://docs.spring.io/spring-boot/reference/actuator/endpoints.html#actuator.endpoints.kubernetes-probes)
- [Docker: Multi-stage builds](https://docs.docker.com/build/building/multi-stage/)
- [PostgreSQL: Backup and Restore](https://www.postgresql.org/docs/17/backup.html)
