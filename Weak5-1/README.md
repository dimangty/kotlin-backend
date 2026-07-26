# Неделя 5-1. Составные и специальные индексы, EXPLAIN ANALYZE (Spring-вариант)

**Результат недели:** каждый индекс из миграции привязан к конкретному запросу сервиса и подтверждён планом с actual rows и buffers, полученным прямо из API.

Сначала пройдите SQL-first лабораторию [Weak5](../Weak5/) — исходный скрипт на миллион строк сохранён здесь в `lab.sql`. Теория недели: [THEORY-SHORT.md](THEORY-SHORT.md) и [THEORY-DETAILED.md](THEORY-DETAILED.md).

## Теория и где она в коде

| Тема плана | Где в проекте |
|---|---|
| Составной covering index: equality → range/sort, INCLUDE | `payments_user_created_cover_idx` в [V1__payments_and_indexes.sql](src/main/resources/db/migration/V1__payments_and_indexes.sql), запрос `history` в [PaymentHistoryService.kt](src/main/kotlin/study/week5copy/PaymentHistoryService.kt) |
| Порядок колонок: контрпример | `payments_created_user_idx` с обратным порядком в той же миграции |
| Partial index для редких незавершённых операций | `payments_pending_idx` (`WHERE status = 'PENDING'`, ~2% строк) и запрос `pendingBefore` |
| Expression index | `payments_reference_lower_idx` на `lower(reference)` |
| GIN и BRIN | `payments_metadata_gin_idx` (jsonb) и `payments_created_brin_idx` |
| Index Only Scan и visibility map | `VACUUM (ANALYZE)` в `generate`; проверка `Heap Fetches` в интеграционном тесте |
| EXPLAIN (ANALYZE, BUFFERS) из приложения | `explainHistory` и маршрут `/history/plan` |

## Запуск

```bash
docker compose up -d
./gradlew bootRun
```

PostgreSQL слушает `localhost:5435`. Полезные маршруты:

- `POST /api/payments/generate` с JSON `{"count":50000}`
- `GET /api/payments/history?userId=42&from=2025-01-01T00:00:00Z`
- `GET /api/payments/history/plan?userId=42&from=2025-01-01T00:00:00Z`
- `GET /api/payments/pending?before=2026-07-22T00:00:00Z`
- `GET /api/payments/indexes`

`lab.sql` рассчитан на отдельную пустую базу: его таблица `payments` намеренно не совпадает с Flyway lifecycle приложения.

Проверка: `./gradlew test` подтверждает Index Only Scan и наличие всех учебных индексов на PostgreSQL 17.

## Задания

1. Сравнить `(user_id, created_at)` и `(created_at, user_id)` на одинаковых данных: заставить planner использовать каждый и объяснить разницу в buffers.
2. Зафиксировать для запроса истории таблицу: план до → индекс → план после → execution time → buffers → размер индекса → цена массового INSERT.
3. После `UPDATE` части строк проверить рост `Heap Fetches` в плане, выполнить `VACUUM` и показать возврат к нулю.
4. Подобрать запросы, в которых planner реально использует expression, GIN и BRIN индексы; объяснить, для каких задач нужен GiST.
5. Убрать константу `status = 'PENDING'` из `pendingBefore` (сделать статус параметром) и показать, что partial index перестал применяться.

## Что разобрать с ментором

- Review каждого индекса только вместе с SQL-запросом и `EXPLAIN ANALYZE` — по правилу трека.
- Ошибочные оценки cardinality: где `estimated rows` разошлись с `actual rows` и что сделал `ANALYZE`.
- Сколько стоит хранить шесть индексов на таблице платежей и какие из них выжили бы в проде.

## Критерий готовности

- Для каждого индекса называешь запрос, который он ускоряет, и операции, которые он замедляет.
- Объясняешь leftmost behavior составного индекса без фразы «так принято».
- Можешь назвать условия Index Only Scan и показать их выполнение планом.

## Контрольные вопросы

- Почему индексы `(status, user_id)` и `(user_id, status)` неэквивалентны?
- Чем Index Scan отличается от Bitmap Heap Scan?
- Когда partial index безопасен и полезен, а когда он «теряется» планировщиком?
- Почему наличие всех колонок в INCLUDE ещё не гарантирует Index Only Scan?

## Материалы

- [PostgreSQL: Multicolumn Indexes](https://www.postgresql.org/docs/17/indexes-multicolumn.html) — порядок колонок.
- [PostgreSQL: Index-Only Scans and Covering Indexes](https://www.postgresql.org/docs/17/indexes-index-only-scans.html) — visibility map.
- [PostgreSQL: Partial Indexes](https://www.postgresql.org/docs/17/indexes-partial.html).
- [PostgreSQL: GIN and BRIN Indexes](https://www.postgresql.org/docs/17/gin.html) — специальные типы.
