# Review

Статус: принято. Testcontainers 2.0.5 запустил настоящий PostgreSQL 17 на Docker ARM64; UNIQUE test и синхронизированный parallel debit test проходят. Futures ограничены timeout, executor гарантированно закрывается в `finally`.

При ревью исправлены return type setup method, Docker Desktop socket discovery и совместимость с Docker Engine 29.
