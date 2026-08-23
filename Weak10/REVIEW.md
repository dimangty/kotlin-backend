# Review

Обновление 23 августа 2026: `Weak10` полностью перестроен по материалам `test/course-catalog-service-9…11` и перенесён на актуальный стек репозитория — Kotlin 2.3.21, Spring Boot 4.1.0, Jakarta Persistence/Validation, PostgreSQL 17, Flyway и Testcontainers 2.0.5.

Статус: принято. `./gradlew test` проходит; unit-тесты фиксируют service rule, интеграционные тесты проверяют полный HTTP/JPA/PostgreSQL-срез.

## Что исправлено относительно примеров

- JPA entity больше не используется как API DTO и не объявлена `data class`.
- `javax.*` заменён на `jakarta.*`.
- Неизвестный преподаватель и курс возвращают `404` с машинным `code`, а не случайный `500`.
- Ошибки validation возвращаются структурированно; клиенту не выдаётся сырой `ex.message`.
- Конфигурация больше не смешивает PostgreSQL datasource с H2 dialect.
- Схема создаётся Flyway, `ddl-auto=validate` не меняет данные.
- Derived query имеет стабильную сортировку и нечувствителен к регистру.
- `@EntityGraph` явно загружает преподавателя для response mapping и закрывает базовый N+1.
- Integration test использует настоящий PostgreSQL на динамическом порту.

## Оставшаяся учебная работа

- Добавить endpoint получения одного курса и пагинацию списка.
- Зафиксировать политику удаления преподавателя с существующими курсами.
- Добавить query-count test для N+1 и constraint test внешнего ключа.
- Добавить optimistic locking для конкурентного обновления курса.
