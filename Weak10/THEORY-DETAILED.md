# Неделя 10 — подробная теория

Course Catalog: JPA-связи, validation, error contract и тестирование вертикального среза.

> Главная идея: корректный API защищает данные последовательно. DTO проверяет форму, сервис — бизнес-смысл, PostgreSQL — целостность независимо от пути записи.

---

## 1. Вертикальный срез вместо набора аннотаций

В примерах курса постепенно появляются controller, service, repository, JPA и tests. На этой неделе они соединяются в один сценарий:

1. Клиент создаёт преподавателя.
2. Клиент создаёт курс и передаёт `instructorId`.
3. Controller валидирует JSON.
4. Service убеждается, что преподаватель существует.
5. JPA записывает курс с внешним ключом.
6. PostgreSQL проверяет `NOT NULL`, `CHECK` и `FOREIGN KEY`.
7. API возвращает DTO с идентификаторами и именем преподавателя.

Польза вертикального среза в том, что границы видны на рабочем коде. Можно проверить не только отдельный метод, но и итоговый HTTP-контракт.

## 2. DTO нельзя подменять JPA-сущностью

### 2.1 У моделей разные причины меняться

`Course` меняется, когда меняется схема или persistence mapping. `CourseRequest` меняется, когда меняется контракт клиента. `CourseResponse` меняется, когда API решает показать новое поле. Это три независимых причины.

Если вернуть entity напрямую, наружу просачиваются:

- ленивые proxy Hibernate;
- двунаправленные связи и риск бесконечной рекурсии;
- технические поля (`version`, audit, internal status);
- зависимость JSON от открытой Hibernate session;
- возможность случайно изменить API при рефакторинге persistence-модели.

### 2.2 Request и response тоже полезно разделять

В запросе создания нет серверного `id`, а в ответе он обязателен. В большом API разумно иметь `CreateCourseRequest`, `UpdateCourseRequest` и `CourseResponse`. В лаборатории create/update используют общий `CourseRequest`, потому что их поля пока совпадают.

## 3. Моделирование Course–Instructor

### 3.1 Реляционная модель

```sql
CREATE TABLE courses (
    id bigserial PRIMARY KEY,
    name varchar(200) NOT NULL,
    instructor_id bigint NOT NULL REFERENCES instructors(id)
);
```

Физически связь хранится в `courses.instructor_id`, поэтому именно `Course` является owning side JPA-связи.

### 3.2 JPA mapping

```kotlin
@ManyToOne(fetch = FetchType.LAZY, optional = false)
@JoinColumn(name = "instructor_id", nullable = false)
var instructor: Instructor
```

На обратной стороне:

```kotlin
@OneToMany(mappedBy = "instructor")
val courses: MutableList<Course>
```

`mappedBy` указывает имя Kotlin-поля на owning side, а не имя SQL-колонки.

### 3.3 Почему `LAZY`

Для курса преподаватель нужен не во всех сценариях, поэтому eager loading по умолчанию создаёт скрытые запросы и раздувает object graph. `LAZY` делает загрузку явной. Для endpoint списка лаборатория использует `@EntityGraph`, потому что response требует `instructorName`.

## 4. Три уровня защиты входных данных

### 4.1 Bean Validation: форма запроса

`@NotBlank`, `@Size`, `@Positive` проверяют локальные свойства DTO. Они не обращаются в базу и не реализуют бизнес-сценарий.

Controller активирует проверку через `@Valid`:

```kotlin
fun create(@Valid @RequestBody request: CourseRequest)
```

Если забыть `@Valid`, constraints останутся метаданными и запрос попадёт в сервис.

### 4.2 Service: бизнес-смысл

Положительный `instructorId` ещё не означает, что преподаватель существует. Сервис загружает его или выбрасывает `InstructorNotFound`. Это даёт клиенту осмысленный `404` до попытки вставки.

### 4.3 PostgreSQL: инвариант

Внешний ключ нужен даже при service check:

- данные могут загружаться batch/job-ом;
- другой endpoint может забыть проверку;
- между проверкой и записью возможна конкурентная операция;
- администратор или миграция могут писать напрямую.

Service делает ошибку понятной. Constraint делает неправильное состояние невозможным.

## 5. Error contract

Плохой вариант из учебных прототипов — вернуть `ex.message` строкой. Он нестабилен, зависит от библиотеки и может показать SQL, имя таблицы или внутренний класс.

Лаборатория использует фиксированную форму:

```kotlin
data class ApiError(
    val code: String,
    val message: String,
    val details: Map<String, String>,
    val requestId: String,
)
```

Клиент принимает решение по `status` и `code`, а не разбирает человеческий текст. `details` хранит ошибки отдельных полей. `requestId` связывает ответ с логами.

### 5.1 Карта статусов

| Ситуация | HTTP | Code |
|---|---:|---|
| Нечитаемый JSON | 400 | `MALFORMED_REQUEST` |
| Нарушены DTO constraints | 400 | `VALIDATION_FAILED` |
| Нет преподавателя | 404 | `INSTRUCTOR_NOT_FOUND` |
| Нет курса | 404 | `COURSE_NOT_FOUND` |
| Конфликт версии/состояния | 409 | добавляется в задании |
| Неожиданная ошибка | 500 | не раскрывает внутренние детали |

## 6. Поиск и N+1

### 6.1 Derived query

Spring Data строит запрос из имени:

```kotlin
findAllByNameContainingIgnoreCaseOrderByNameAsc(courseName: String)
```

Для простого одного фильтра это читаемо. Когда появляются несколько необязательных фильтров, сложная сортировка или projection, лучше перейти к явному JPQL, Specification/Querydsl либо SQL.

### 6.2 Почему появляется N+1

Если сначала выбрать N курсов, а затем при маппинге DTO обратиться к `course.instructor.name`, Hibernate может выполнить ещё N запросов. `@EntityGraph(attributePaths = ["instructor"])` говорит конкретному repository method загрузить нужную связь одним запросом.

`open-in-view=false` помогает: ленивую связь нельзя случайно догрузить из controller после завершения service transaction. Ошибка обнаруживается ближе к неверной границе.

## 7. Transaction boundaries

Транзакции принадлежат service-методам:

- `create`, `update`, `delete` — read/write transaction;
- `findAll`, `InstructorService.require` — `readOnly=true`;
- controller не знает о persistence context;
- repository отвечает за доступ к данным, но не задаёт бизнес-сценарий.

В `update` Hibernate dirty checking записывает изменённые поля при commit. Явный второй `save` для уже managed entity не нужен.

## 8. Flyway вместо Hibernate DDL

`generate-ddl` и `ddl-auto=create-drop` удобны для прототипа, но не дают ревьюируемую историю изменений и опасны для реальных данных.

В лаборатории:

```yaml
spring.jpa.hibernate.ddl-auto: validate
spring.flyway.enabled: true
```

Flyway создаёт схему из `V1__course_catalog.sql`. Hibernate сравнивает mapping с готовой схемой и падает при несовпадении. Так SQL остаётся версионированным артефактом проекта.

## 9. Стратегия тестов

### 9.1 Unit test сервиса

`CourseServiceTest` не поднимает Spring. Он проверяет две бизнес-ветки:

- существующий преподаватель связывается с курсом;
- неизвестный преподаватель останавливает сценарий до `save`.

Такой тест быстрый и точно локализует правило.

### 9.2 Integration test

`CourseCatalogIntegrationTest` поднимает:

- Spring Boot context;
- MockMvc и JSON mapping;
- Bean Validation и `ControllerAdvice`;
- JPA repositories и transaction management;
- Flyway migrations;
- настоящий PostgreSQL 17 через Testcontainers.

Он проверяет lifecycle курса, фильтрацию, DTO relation и negative cases. H2 не используется: даже простая JPA-схема должна тестироваться в том диалекте, где будет работать.

### 9.3 Что должен ловить тест

Полезная ручная mutation-проверка:

1. Уберите `@Valid` — validation test должен покраснеть.
2. Уберите service check преподавателя — not-found contract должен измениться и тест упасть.
3. Уберите `@EntityGraph`, добавьте query counter — N+1 test должен показать рост запросов.
4. Уберите FK из миграции — отдельный constraint test должен покраснеть (его предлагается добавить).

## 10. Типичные ошибки

1. Entity используется как request и response.
2. `@Valid` забыли на параметре controller.
3. Существование FK проверяется только аннотацией `@Positive`.
4. Исключение превращается в `500` с текстом драйвера.
5. `CourseNotFound` возвращается как `500`, а не `404`.
6. `FetchType.EAGER` включается глобально для починки N+1.
7. `open-in-view=true` скрывает неверные transaction boundaries.
8. Hibernate сам создаёт/удаляет схему вместо миграций.
9. Integration test использует H2 при production PostgreSQL.
10. Native SQL применяется там, где простой derived query выражает намерение яснее.

---

## Итог

Вертикальный срез считается готовым не тогда, когда `POST` однажды вернул `201`, а когда его границы формализованы: DTO валидируется, service rule названа, relation закреплена FK, schema версионируется, ошибки стабильны, а позитивные и негативные сценарии выполняются на настоящем PostgreSQL.
