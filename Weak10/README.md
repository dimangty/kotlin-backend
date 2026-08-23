# Неделя 10. Course Catalog: JPA-связи, validation и error contract

**Результат недели:** собрать полноценный вертикальный срез каталога курсов: преподаватель хранится отдельно, курс ссылается на него через внешний ключ, входные DTO валидируются на HTTP-границе, а ошибки имеют стабильный JSON-контракт.

Теория недели: [THEORY-SHORT.md](THEORY-SHORT.md) (шпаргалка) и [THEORY-DETAILED.md](THEORY-DETAILED.md) (подробный разбор).

Модуль переработан по примерам `course-catalog-service-9…11` из папки `test/`. Из примеров сохранены ключевые идеи — `Course`/`Instructor`, `ManyToOne`, фильтр `course_name`, Bean Validation, `ControllerAdvice`, unit- и integration-тесты. Устаревшие и небезопасные решения заменены современными: `jakarta.*`, DTO вместо выдачи entity, Flyway вместо `generate-ddl`, PostgreSQL 17 вместо смешения PostgreSQL и H2, структурированные ошибки вместо текста исключения.

## Что строим

```text
POST /v1/instructors
         │
         ▼
   instructors (1) ◄──── (N) courses
                              ▲
                              │
 POST/GET/PUT/DELETE /v1/courses
```

Курс не может существовать без преподавателя. Это правило дублируется в двух местах:

- сервис отклоняет неизвестный `instructorId` понятной ошибкой `404`;
- PostgreSQL гарантирует `NOT NULL` и `FOREIGN KEY`, даже если запись пришла в обход HTTP API.

## Теория и где она в коде

| Тема | Где смотреть |
|---|---|
| DTO не равен entity | [CourseApiModels.kt](src/main/kotlin/study/week10/CourseApiModels.kt) и [Course.kt](src/main/kotlin/study/week10/Course.kt) |
| `ManyToOne` / `OneToMany` | [Course.kt](src/main/kotlin/study/week10/Course.kt) и [Instructor.kt](src/main/kotlin/study/week10/Instructor.kt) |
| Владелец JPA-связи | `Course.instructor` содержит `@JoinColumn`; `Instructor.courses` использует `mappedBy` |
| Validation на границе | `@Valid` в [CourseController.kt](src/main/kotlin/study/week10/CourseController.kt), constraints в `CourseRequest` |
| Единый контракт ошибок | [ApiErrorHandler.kt](src/main/kotlin/study/week10/ApiErrorHandler.kt): `VALIDATION_FAILED`, `INSTRUCTOR_NOT_FOUND`, `COURSE_NOT_FOUND` |
| Derived query | [CourseRepository.kt](src/main/kotlin/study/week10/CourseRepository.kt): `findAllByNameContainingIgnoreCaseOrderByNameAsc` |
| Защита от N+1 | `@EntityGraph(attributePaths = ["instructor"])` загружает нужную связь для списка |
| Схема как код | [V1__course_catalog.sql](src/main/resources/db/migration/V1__course_catalog.sql), Hibernate только проверяет её через `ddl-auto: validate` |
| Реальная интеграция | [CourseCatalogIntegrationTest.kt](src/test/kotlin/study/week10/CourseCatalogIntegrationTest.kt) запускает Spring MVC + JPA + Flyway + PostgreSQL Testcontainers |
| Изолированное бизнес-правило | [CourseServiceTest.kt](src/test/kotlin/study/week10/CourseServiceTest.kt) проверяет связь с преподавателем без Spring-контекста |

## Запуск

Нужны JDK 17 и Docker.

```bash
./gradlew test
```

Тесты сами поднимают PostgreSQL 17 на случайном порту. Для ручного запуска приложения:

```bash
docker compose up -d
./gradlew bootRun
```

Если порт `5438` занят:

```bash
PG_PORT=55438 docker compose up -d
DB_URL=jdbc:postgresql://localhost:55438/courses ./gradlew bootRun
```

## Проверка API

Создать преподавателя:

```bash
curl -i -H 'Content-Type: application/json' \
  -d '{"name":"Dilip Sundarraj"}' \
  http://localhost:8080/v1/instructors
```

Создать курс, подставив полученный `id`:

```bash
curl -i -H 'Content-Type: application/json' \
  -d '{"name":"Kotlin Spring Boot","category":"Development","instructorId":1}' \
  http://localhost:8080/v1/courses
```

Получить все курсы или отфильтровать по части имени:

```bash
curl -i http://localhost:8080/v1/courses
curl -i 'http://localhost:8080/v1/courses?course_name=spring'
```

Проверить негативный сценарий:

```bash
curl -i -H 'Content-Type: application/json' \
  -d '{"name":" ","category":"","instructorId":0}' \
  http://localhost:8080/v1/courses
```

Ответ имеет стабильную форму:

```json
{
  "code": "VALIDATION_FAILED",
  "message": "Request is invalid",
  "details": {
    "category": "must not be blank",
    "instructorId": "must be greater than 0",
    "name": "must not be blank"
  },
  "requestId": "..."
}
```

## Задания

1. **Получение одного курса.** Добавьте `GET /v1/courses/{id}` и тесты на `200`/`404`. Не возвращайте JPA-сущность напрямую.
2. **Фильтр по преподавателю.** Поддержите `instructor_id` вместе с `course_name`. Решите, какие комбинации параметров допустимы, и закрепите контракт тестами.
3. **Удаление преподавателя.** Реализуйте осознанную политику: запретить удаление с курсами (`409`) или каскадно удалить их. Сначала сформулируйте бизнес-правило, затем измените FK и код.
4. **Пагинация.** Замените неограниченный список на `Pageable`, введите максимальный `size`, добавьте стабильную сортировку.
5. **Проверка N+1.** Уберите `@EntityGraph`, включите статистику Hibernate и посчитайте запросы для списка из 20 курсов; верните защиту и сравните.
6. **Optimistic locking.** Добавьте `@Version` к курсу, передавайте версию в update DTO и возвращайте `409` для устаревшего изменения.

## Что разобрать с ментором

- Почему `Course` владеет связью, хотя со стороны бизнеса преподаватель «имеет курсы».
- Где заканчивается HTTP-validation и начинаются бизнес-правила сервиса.
- Почему универсальный `catch (Exception)` с `ex.message` создаёт нестабильный контракт и может раскрыть внутренние детали.
- Когда derived query читается хорошо, а когда лучше явный JPQL/SQL.
- Зачем одновременно нужны service check и внешний ключ.

## Критерий готовности

- `./gradlew test` проходит на чистом PostgreSQL Testcontainers.
- Нельзя создать курс с пустыми полями или неизвестным преподавателем.
- Все ответы API используют DTO и не сериализуют ленивые JPA-связи.
- Фильтр нечувствителен к регистру и возвращает стабильный порядок.
- Flyway создаёт схему, Hibernate её только валидирует.
- Ошибки validation/not-found различимы по HTTP-статусу и полю `code`.

## Контрольные вопросы

- Какая сторона связи хранит `instructor_id` и почему именно она является owning side?
- Чем `@Valid` отличается от проверки существования `instructorId`?
- Почему `open-in-view=false` быстрее обнаруживает неверные transaction boundaries?
- Как `@EntityGraph` связан с проблемой N+1?
- Почему H2 не является эквивалентом PostgreSQL даже для «простого» JPA-теста?

## Материалы

- [Spring Data JPA Reference](https://docs.spring.io/spring-data/jpa/reference/)
- [Spring Framework: Validation](https://docs.spring.io/spring-framework/reference/core/validation/beanvalidation.html)
- [Hibernate ORM: Associations](https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#associations)
- [Flyway Documentation](https://documentation.red-gate.com/flyway)
- [Testcontainers PostgreSQL Module](https://java.testcontainers.org/modules/databases/postgres/)
