# Review

Миграция 24 июля 2026: Spring Boot 4.1.0, Kotlin 2.3.21 и модульный Web MVC test starter; readiness-тест проходит.

Статус: Gradle readiness test и workflow проходят. Multi-stage image заново собран на ARM64 из Docker context 2.3 KB: readiness=200/UP, user=`app`, root filesystem read-only. `.dockerignore` исключает локальные build/cache файлы, Dockerfile использует проектный wrapper.

При ревью Alpine runtime заменён на официальный multi-arch Temurin Jammy. Для production нужны pinned image digests, SBOM/scan и migration/rollback job.

Добавлен `docs/safe-deploy.md`: expand-contract в три релиза, поведение при двух одновременно стартующих instance, правила отката (откатывается приложение, схема остаётся совместимой с обеими версиями) и процедура backup/restore. Числа по стоимости миграций взяты из фактического прогона `Weak8/migration-lab.sql`, а цикл `pg_dump -Fc` -> `CREATE DATABASE` -> `pg_restore` -> проверка запросом выполнен на PostgreSQL 17.
