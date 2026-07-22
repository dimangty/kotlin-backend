# Review

Статус: Gradle readiness test и workflow проходят. Multi-stage image заново собран на ARM64 из Docker context 2.3 KB: readiness=200/UP, user=`app`, root filesystem read-only. `.dockerignore` исключает локальные build/cache файлы, Dockerfile использует проектный wrapper.

При ревью Alpine runtime заменён на официальный multi-arch Temurin Jammy. Для production нужны pinned image digests, SBOM/scan и migration/rollback job.
