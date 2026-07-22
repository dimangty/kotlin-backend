# Неделя 10. Testcontainers и concurrency tests

Тесты запускают настоящий PostgreSQL, проверяют UNIQUE/CHECK и синхронизируют конкурентов через latch.

```bash
./gradlew test   # нужен работающий Docker
```

## Review checklist

- Тест действительно падает после удаления constraint/atomic predicate.
- Конкуренция начинается барьером, а не надеждой на случайный race.
- Проверяется бизнес-инвариант, а не внутренний вызов mock.
- Контейнер не зависит от локальной базы или порта 5432.

Задание: добавить тест двух проводок, retry serialization failure и прогон Flyway migrations на пустой базе.

