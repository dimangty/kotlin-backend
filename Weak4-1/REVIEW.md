# Code review — Weak4-1

Дата: 22 июля 2026. Вердикт: готово, блокирующих замечаний нет.

## Проверено

- `./gradlew test`: 2 Testcontainers integration tests, failures/skips — 0.
- На 5 000 events PostgreSQL выбирает `Index Scan` по `events_public_id_key`.
- API возвращает `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)`, распределение статусов и размеры heap/indexes.
- Миграция создаёт bigint PK, UUID UNIQUE, timestamp и low-cardinality status B-tree.
- Compose-файл проходит `docker compose config -q`.

## Исправлено во время review

- SQL-first `lab.sql` отделён в документации от Flyway-схемы: обе лаборатории создают `events` и не должны запускаться в одной базе.
- Testcontainers core и PostgreSQL module выровнены на совместимую версию 2.0.5.

## Сильные стороны

- Высокая и низкая selectivity показаны на одной модели.
- `ANALYZE` выполняется после bulk load, поэтому эксперимент не зависит от устаревшей статистики.
- Размер генерации ограничен на API, комментарии связывают B-tree с реальной I/O стоимостью.

## Учебная работа

Повторить опыт на миллионе строк, сохранить планы Seq/Index Scan и измерить INSERT overhead при разном числе индексов.
