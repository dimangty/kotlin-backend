# Неделя 15. Ktor + PostgreSQL

Явный JDBC transaction wrapper и Hikari pool показывают границу framework/database. Locks берутся в едином порядке, rollback выполняется для любой ошибки.

## Задания

Добавить idempotency UNIQUE, ledger, ownership/JWT, Flyway startup и Testcontainers concurrency test. Затем сравнить `Weak7` и `Weak15` по startup, wiring, SQL transparency и testing — DB invariants должны совпадать.

