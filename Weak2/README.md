# Неделя 2. REST, DTO и валидация

CRUD заметок без базы. Проект разделяет HTTP DTO, доменную модель, repository и service; version подготавливает к optimistic locking.

## Запуск

```bash
./gradlew bootRun
curl -i -H 'Content-Type: application/json' -d '{"title":"REST"}' http://localhost:8080/notes
```

## Учебные точки

- Controller отвечает только за транспорт.
- Service задаёт use case.
- Repository скрывает хранение, но не бизнес-правила.
- Ошибки имеют стабильный `code`, детали и `requestId`.

## Задания

1. Добавить pagination contract, не раскрывая внутреннюю коллекцию.
2. Реализовать PATCH и объяснить семантику отсутствующего/null поля.
3. Написать web-тесты на 201, 204, 400, 404 и 409.

