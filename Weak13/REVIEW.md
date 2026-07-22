# Review

Статус: Gradle-сборка проходит; workflow перенесён в корневой `.github/workflows`, поэтому GitHub Actions видит его и запускает команды в `Weak13`. Multi-stage image ранее проверен на ARM64: readiness=200/UP, non-root uid=999, root filesystem read-only.

При ревью Alpine runtime заменён на официальный multi-arch Temurin Jammy. Для production нужны pinned image digests, SBOM/scan и migration/rollback job.
