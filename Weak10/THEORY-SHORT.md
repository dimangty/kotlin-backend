# Неделя 10 — краткая теория

**Тема:** JPA-связи, validation, error contract и тестирование вертикального среза.

**Результат:** курс создаётся только для существующего преподавателя, API не выдаёт entity наружу, ошибки предсказуемы, а поведение подтверждено на настоящем PostgreSQL.

---

## 1. Путь запроса

```text
HTTP JSON
  → Controller + @Valid
  → Service + business rule
  → Repository + transaction
  → PostgreSQL constraints
  → Response DTO / ApiError
```

Каждый слой отвечает за свой класс ошибок:

| Слой | Проверяет | Пример |
|---|---|---|
| HTTP / DTO | форма запроса | пустое `name`, `instructorId <= 0` |
| Service | бизнес-смысл | преподавателя с таким id нет |
| PostgreSQL | целостность при любом пути записи | `NOT NULL`, `CHECK`, `FOREIGN KEY` |

## 2. DTO и entity — разные модели

JPA-сущность описывает хранение, ленивые связи и жизненный цикл persistence context. DTO описывает публичный HTTP-контракт.

Нельзя возвращать entity напрямую: легко получить рекурсию `Instructor → courses → instructor`, `LazyInitializationException`, лишние поля или случайное изменение JSON после рефакторинга базы.

## 3. Владелец связи

```kotlin
@ManyToOne(fetch = FetchType.LAZY)
@JoinColumn(name = "instructor_id")
var instructor: Instructor
```

`Course` — owning side, потому что таблица `courses` хранит внешний ключ. `Instructor.courses` — обратная сторона с `mappedBy = "instructor"`.

## 4. Validation не заменяет бизнес-правила

```kotlin
data class CourseRequest(
    @field:NotBlank val name: String,
    @field:Positive val instructorId: Long,
)
```

`@Positive` доказывает только то, что число больше нуля. Существование преподавателя проверяет сервис. Внешний ключ остаётся последней защитой базы.

## 5. Стабильный контракт ошибок

```json
{
  "code": "INSTRUCTOR_NOT_FOUND",
  "message": "Instructor 42 not found",
  "details": {},
  "requestId": "..."
}
```

- `400` — запрос невозможно принять или провалена Bean Validation;
- `404` — запрошенный курс/преподаватель не найден;
- `409` — конфликт состояния, если он появится в заданиях;
- `500` — внутренняя ошибка без stack trace и деталей драйвера в ответе.

## 6. Derived query и N+1

`findAllByNameContainingIgnoreCaseOrderByNameAsc` даёт читаемый запрос для простого фильтра. `@EntityGraph("instructor")` загружает преподавателя вместе с курсами списка и не создаёт отдельный запрос на каждую строку.

## 7. Схема и тесты

- Flyway владеет DDL; `ddl-auto: validate` ловит расхождение entity и схемы.
- Unit-тест сервиса быстро проверяет бизнес-ветвления.
- Integration-тест проверяет Spring MVC, сериализацию, validation, транзакции, mapping и PostgreSQL одной цепочкой.
- H2 не проверяет точный диалект, FK/DDL и поведение PostgreSQL.

---

## Контрольные вопросы

1. Где физически хранится связь Course–Instructor?
2. Почему одного `@Positive instructorId` недостаточно?
3. Зачем DTO содержит `instructorId`, но не объект `Instructor`?
4. Что произойдёт без `@EntityGraph` при выдаче большого списка?
5. Какой тест должен поймать удаление внешнего ключа?
