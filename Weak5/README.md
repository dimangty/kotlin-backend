# Неделя 5. Составные и специальные индексы, EXPLAIN ANALYZE

**Результат недели:** индекс выбирается по конкретному запросу и подтверждается планом выполнения, а не интуицией.

Данные со skew: только около 2% платежей имеют статус `PENDING`.

## Запуск

```bash
docker compose up -d
docker compose exec postgres psql -U study -d plans
```

Если 5432 занят: `PG_PORT=55432 docker compose up -d`.

## Часть 1. Составные индексы (`lab.sql`)

Выполняется автоматически при первом старте; вывод - в `docker compose logs postgres`.

Три эксперимента подряд:

1. **История платежей.** `WHERE user_id = 42 AND created_at >= ... ORDER BY created_at DESC LIMIT 50` до и после `(user_id, created_at DESC) INCLUDE (id, amount_minor)`. После `VACUUM` план становится Index Only Scan с `Heap Fetches: 0`.
2. **Порядок колонок.** Тот же запрос на `(created_at DESC, user_id)`. Колонки те же, порядок другой - и это меняет всё:

   | Индекс | План | Buffers | Время |
   |---|---|---|---|
   | `(user_id, created_at)` | Index Only Scan | ~7 | ~0,04 ms |
   | `(created_at, user_id)` | Index Scan | ~489 | ~2,0 ms |

   Ведущая колонка `created_at` заставляет пройти всех пользователей за 90 дней и отфильтровать чужие строки.
3. **Planner отказывается от индекса.** Агрегат по 400 дням берёт слишком большую долю таблицы: Seq Scan дешевле. `SET enable_seqscan = off` показывает, во что обошёлся бы индекс - сравнивать нужно `Buffers`, а не время на прогретом cache.

## Часть 2. Специальные индексы (`special-indexes.sql`)

```bash
docker compose exec -T postgres psql -U study -d plans -v ON_ERROR_STOP=1 < special-indexes.sql
```

Миллион строк append-only лога с jsonb-полем. Что демонстрируется:

- **Expression index.** Обычный индекс по `email` не помогает запросу `WHERE lower(email) = ...`: индексируется одно выражение, ищется другое. `~53 ms -> ~0,5 ms` после `CREATE INDEX ON operations(lower(email))`.
- **GIN.** На `metadata @> '{"channel":"ios"}'` индекс не помогает: predicate забирает треть таблицы. Тот же GIN на селективном `merchant` даёт `~0,3 ms`. Урок недели 4 про low-cardinality повторяется, просто с другим типом индекса.
- **BRIN.** На физически упорядоченной `created_at` недельный диапазон читается за то же время, что через B-tree, но индекс занимает **32 kB против 21 MB**. Плата за это видна в плане: `Heap Blocks: lossy` и `Rows Removed by Index Recheck`.

Последний запрос печатает `correlation` из `pg_stats` - это и есть условие, при котором BRIN работает.

## Задания

1. Для каждого запроса собрать таблицу: SQL -> распределение данных -> план до -> индекс -> план после -> buffers -> размер индекса -> влияние на массовый INSERT (замер из `Weak4/write-overhead.sql`).
2. Объяснить, почему partial `payments_pending_idx` настолько мал, и что произойдёт с запросом, если predicate станет `status IN ('PENDING','FAILED')`.
3. Перемешать физический порядок `operations` (`UPDATE ... SET created_at = created_at`), выполнить `ANALYZE` и показать, как падение `correlation` ломает BRIN.
4. Добавить поиск по массиву `tags` (`metadata @> '{"tags":["retry"]}'`) и определить, какой класс операторов GIN для этого нужен.
5. Найти запрос, для которого covering index перестаёт давать Index Only Scan, и объяснить роль visibility map.

## Что разобрать с ментором

- Review индекса только вместе с SQL и `EXPLAIN (ANALYZE, BUFFERS)`, никогда отдельно.
- Ошибочные оценки cardinality: где `estimated rows` сильно разошлись с `actual rows` и почему.
- Какие из созданных индексов вы бы не завели в production и почему.

## Критерий готовности

- Для каждого индекса можешь назвать запрос, который он ускоряет, и операции, которые он замедляет.
- Можешь объяснить leftmost behavior без фразы "так принято".
- Можешь назвать условие, при котором BRIN бесполезен, до запуска эксперимента.

## Контрольные вопросы

- Почему `(status, user_id)` и `(user_id, status)` неэквивалентны?
- Чем Index Scan отличается от Bitmap Heap Scan?
- Когда partial index безопасен и полезен?
- Почему наличие всех нужных колонок в индексе ещё не гарантирует Index Only Scan?

## Материалы

- [PostgreSQL: Multicolumn Indexes](https://www.postgresql.org/docs/17/indexes-multicolumn.html)
- [PostgreSQL: Index-Only Scans](https://www.postgresql.org/docs/17/indexes-index-only-scans.html)
- [PostgreSQL: Using EXPLAIN](https://www.postgresql.org/docs/17/using-explain.html)
- [PostgreSQL: BRIN Indexes](https://www.postgresql.org/docs/17/brin.html)
- [PostgreSQL: GIN Indexes](https://www.postgresql.org/docs/17/gin.html)
