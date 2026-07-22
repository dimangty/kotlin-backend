# Неделя 8. Spring Data, JDBC/JPA и migrations

Один use case использует JPA lifecycle/dirty checking, аналитический запрос — явный SQL через `JdbcTemplate`. Flyway владеет схемой, Hibernate только валидирует её.

```bash
docker compose up -d
./gradlew bootRun
```

## Задания

1. Добавить controller и integration tests.
2. Создать связь account -> payments, воспроизвести N+1 и исправить projection/fetch join.
3. Спроектировать expand-contract миграцию обязательной колонки без долгой блокировки.
4. Включить SQL logs и приложить `EXPLAIN (ANALYZE, BUFFERS)` для daily totals.

