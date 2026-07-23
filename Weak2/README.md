# Неделя 2. Spring MVC, REST, DTO и валидация

**Результат недели:** сделать корректный CRUD без базы данных и развести транспортный, бизнес- и инфраструктурный код так, чтобы граница была видна по файлам, а не по договорённости.

Базы здесь ещё нет специально: пока история хранится в `ConcurrentHashMap`, ничто не маскирует ошибки в контракте API. Поле `version` в модели - подготовка к неделе 6: конкурентное обновление появляется раньше, чем PostgreSQL.

## Три модели, а не одна

Главная ошибка недели - использовать один класс как request body, доменную модель и запись в базе. Тогда любое изменение схемы становится ломающим изменением мобильного API.

| Модель | Класс | Кто ей владеет |
|---|---|---|
| Request DTO | `CreateNoteRequest`, `UpdateNoteRequest` | контракт с клиентом, меняется только совместимо |
| Domain | `Note` | бизнес-правила |
| Response DTO | `NoteResponse` | то, что разрешено показывать наружу |

На неделе 8 добавится четвёртая модель - persistence entity, и правило останется тем же.

## Теория и где она в проекте

| Тема | Где смотреть |
|---|---|
| Границы Controller / Service / Repository | [NoteController.kt](src/main/kotlin/study/week2/NoteController.kt) - только транспорт; [NoteService.kt](src/main/kotlin/study/week2/NoteService.kt) - use case; [InMemoryNoteRepository.kt](src/main/kotlin/study/week2/InMemoryNoteRepository.kt) - хранение |
| Bean Validation | `@field:NotBlank`, `@field:Size`, `@field:PositiveOrZero` в request DTO |
| Единый формат ошибок | [ApiErrorHandler.kt](src/main/kotlin/study/week2/ApiErrorHandler.kt) - `code`, `message`, `details`, `requestId` для всех четырёх классов ошибок |
| Коды ответов | `201` на create, `200` на update, `204` на delete, плюс `400`, `404`, `409` |
| Optimistic version | `save(note, expectedVersion)` внутри `compute`: проверка версии и запись не разделены гонкой |
| DI через конструктор | все бины принимают зависимости конструктором, field injection не используется |

## Запуск

```bash
./gradlew test
./gradlew bootRun
```

Полный цикл, который проверяет и HTTP-тест:

```bash
# 201 Created
curl -i -H 'Content-Type: application/json' -d '{"title":"REST","body":"first"}' localhost:8080/notes
# 200 OK - подставьте id и version из предыдущего ответа
curl -i -X PUT -H 'Content-Type: application/json' \
     -d '{"title":"REST v2","body":"second","version":0}' localhost:8080/notes/<id>
# 409 Conflict - тот же version повторно
curl -i -X PUT -H 'Content-Type: application/json' \
     -d '{"title":"stale","body":"","version":0}' localhost:8080/notes/<id>
# 400 с деталями по полям
curl -i -H 'Content-Type: application/json' -d '{"title":"  "}' localhost:8080/notes
# 204, затем 404
curl -i -X DELETE localhost:8080/notes/<id>
curl -i localhost:8080/notes/<id>
```

Заголовок `X-Request-Id` попадает в тело ошибки: тот же идентификатор свяжет HTTP-запрос, лог и транзакцию на неделе 12.

## Задания

1. **Pagination contract.** Заменить `GET /notes`, возвращающий всю коллекцию, на страницу: `?limit=&cursor=`. Ответ - объект с `items` и `nextCursor`, а не голый массив. Объяснить, почему offset-pagination даёт дубли и пропуски на изменяющихся данных, а cursor - нет (на неделе 16 cursor уже используется для ledger).
2. **PATCH.** Реализовать `PATCH /notes/{id}` и сформулировать разницу трёх случаев: поле отсутствует (не менять), поле равно `null` (очистить), поле имеет значение (записать). Показать, почему обычный Kotlin data class этого различить не умеет, и выбрать решение.
3. **Негативные тесты.** Дополнить web-тесты до полного набора: `201`, `200`, `204`, `400` (validation), `400` (malformed JSON), `400` (нечисловой UUID в path), `404`, `409`.
4. **Эволюция контракта.** Добавить в `NoteResponse` новое поле и убедиться, что старый мобильный клиент не ломается. Затем переименовать существующее поле и объяснить, почему это уже ломающее изменение и как его выкатывают в два релиза.
5. **Проверка границ.** Внести бизнес-правило (например, лимит длины заметки) и убедиться, что оно оказалось в service, а не в контроллере и не в аннотации DTO.

## Что разобрать с ментором

- Проверить, что в контроллере не осталось бизнес-логики, а в сервисе - HTTP-понятий.
- Эволюция DTO для старых мобильных клиентов: что можно менять молча, а что нельзя никогда.
- Где заканчивается Bean Validation и начинается бизнес-правило.

## Критерий готовности

- Каждый endpoint имеет корректный код ответа и покрытый негативный сценарий.
- DTO не используются как доменная модель и не будут использоваться как JPA entity.
- Формат ошибки одинаков для всех классов ошибок и содержит `requestId`.

## Контрольные вопросы

- Чем `PUT` отличается от `PATCH` по семантике и по идемпотентности?
- Почему `409` для устаревшей версии, а не `400` и не `412`?
- Что вернуть на `DELETE` уже удалённого ресурса - `204` или `404`, и почему это решение контракта, а не вкуса?
- Почему `ConcurrentHashMap.compute` в repository делает ровно то же, что позже сделает `UPDATE ... WHERE version = ?`?

## Материалы

- [Spring Framework: Annotated Controllers](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller.html)
- [Spring Boot: Validation](https://docs.spring.io/spring-boot/reference/io/validation.html)
- [RFC 9457: Problem Details for HTTP APIs](https://www.rfc-editor.org/rfc/rfc9457)
