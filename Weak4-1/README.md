# Неделя 4-1. B-tree: устройство индекса и процесс поиска (Spring-вариант)

**Результат недели:** цена и польза индекса видны из приложения: API генерирует данные, возвращает реальный `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)` и размеры heap/индексов, а тест подтверждает выбор B-tree.

Сначала пройдите SQL-first лабораторию [Weak4](../Weak4/) на миллионе строк — её скрипт сохранён здесь в `lab.sql` для отдельного ручного эксперимента. Теория недели: [THEORY-SHORT.md](THEORY-SHORT.md) и [THEORY-DETAILED.md](THEORY-DETAILED.md).

## Теория и где она в коде

| Тема плана | Где в проекте |
|---|---|
| B-tree на последовательном bigint и случайном UUID | PK `id` и `UNIQUE public_id` в [V1__events_and_btree_indexes.sql](src/main/resources/db/migration/V1__events_and_btree_indexes.sql) |
| Индекс на timestamp и низкоселективном status | `events_created_at_idx`, `events_status_idx` там же |
| Selectivity: когда planner выбирает Seq Scan | эксперимент со `status='DONE'` (~треть таблицы) через `/plan` |
| EXPLAIN (ANALYZE, BUFFERS) из приложения | `explainUuidLookup` в [IndexLabService.kt](src/main/kotlin/study/week4copy/IndexLabService.kt) |
| Статистика planner и ANALYZE после массовой загрузки | явный `ANALYZE events` в `generate` |
| pg_relation_size против pg_indexes_size | `sizes` в `IndexLabService.kt` и маршрут `/sizes` |

## Запуск

```bash
docker compose up -d
./gradlew bootRun
```

PostgreSQL слушает `localhost:5434`. Маршруты:

- `POST /api/index-lab/events/generate` с JSON `{"count":10000}`
- `GET /api/index-lab/events/{publicId}`
- `GET /api/index-lab/events/{publicId}/plan`
- `GET /api/index-lab/distribution`
- `GET /api/index-lab/sizes`

`lab.sql` создаёт собственную таблицу, поэтому запускайте его в отдельной пустой базе, а не поверх Flyway-схемы приложения.

Проверка: `./gradlew test` запускает PostgreSQL 17 через Testcontainers и подтверждает выбор UUID B-tree.

## Задания

1. Сравнить план UUID lookup до и после удаления/возврата индекса (`DROP INDEX` / `CREATE INDEX` прямо в psql, план — через `/plan`).
2. Увеличить данные до миллиона строк и объяснить, почему `status='DONE'` обычно даёт Seq Scan, а точечный UUID — Index Scan.
3. Сравнить размер и скорость вставки при 0, 1 и 3 вторичных индексах.
4. Нарисовать путь root → internal → leaf → heap tuple и объяснить, почему `O(log n)` не описывает I/O стоимость полностью.
5. В выводе `/plan` найти `Buffers: shared hit/read` и объяснить разницу между первым и повторным выполнением одного lookup.

## Что разобрать с ментором

- Последствия случайного UUID против монотонного bigint как первичного ключа — с цифрами из `/sizes`.
- Почему индекс на `status` из трёх значений оставлен в миграции сознательно и когда он всё же станет полезен (partial-форма — неделя 5).
- Как читать `estimated rows` против `actual rows` в JSON-плане и что делать при большом расхождении.

## Критерий готовности

- Без заучивания объясняешь, почему индекс на boolean/трёхзначном status обычно бесполезен сам по себе.
- Понимаешь, что обычный Index Scan дополнительно читает heap.
- Для каждого индекса миграции называешь запрос-выгодоприобретатель и цену на записи.

## Контрольные вопросы

- Что хранится в leaf page B-tree?
- Почему сложность `O(log n)` не описывает всю реальную стоимость запроса?
- Почему индекс ухудшает INSERT и UPDATE?
- Зачем `generate` вызывает `ANALYZE` явно и что будет без него?

## Материалы

- [PostgreSQL: B-Tree Indexes](https://www.postgresql.org/docs/17/btree.html) — внутренняя структура.
- [PostgreSQL: Using EXPLAIN](https://www.postgresql.org/docs/17/using-explain.html) — чтение планов.
- [PostgreSQL: Planner Statistics](https://www.postgresql.org/docs/17/planner-stats.html) — ANALYZE и selectivity.
- [Spring Framework: Data Access with JDBC](https://docs.spring.io/spring-framework/reference/data-access/jdbc.html) — `JdbcTemplate`.
