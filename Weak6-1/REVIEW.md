# Code review — Weak6-1

Дата: 22 июля 2026. Вердикт: готово, блокирующих замечаний нет.

## Проверено

- `./gradlew test`: 3 Testcontainers integration tests, failures/skips — 0.
- 10 concurrent atomic debits сохраняют баланс 0 без lost update.
- Два competing `SELECT FOR UPDATE` списания по 600 дают ровно один успех и итог 400.
- Пять Serializable read-compute-write операций завершаются с итогом 500; тест подтверждает фактический retry после SQLSTATE `40001`.
- Compose-файл с application и SQL-first PostgreSQL проходит `docker compose config -q`.

## Исправлено во время review

- Исходные bigint session scripts вынесены в отдельную `postgres-lab`: они больше не конфликтуют с UUID/Flyway-схемой приложения.
- Retry прекращается при thread interruption и восстанавливает interrupt flag.
- Retry ограничен и повторяет всю бизнес-транзакцию после rollback, а не один UPDATE.

## Сильные стороны

- Два безопасных метода списания показывают разные transaction boundaries и trade-offs.
- DB CHECK остаётся последней защитой неотрицательного остатка.
- Комментарии объясняют MVCC snapshot, row lock и причину полного transaction retry.

## Учебная работа

Для production потребуются метрики retries, jitter/backoff policy и классификация ошибок. Ручные non-repeatable read/lost update/Repeatable Read timelines остаются обязательной частью недели.
