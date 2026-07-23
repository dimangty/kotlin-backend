# Review

Статус: лаборатория повторно прошла на 1 000 000 payments; PENDING ≈2%. Covering и partial indexes дали Index Only Scan с `Heap Fetches: 0`, а history query ускорился примерно с 18,5 мс до 0,06 мс.

Наблюдение: history query выбрал Bitmap Heap Scan + sort, а не слепо Index Scan — это полезный пример того, что planner учитывает cost/statistics.

`lab.sql` дополнен двумя секциями, которых не хватало по плану:

- **Порядок колонок.** Индекс `(user_id, created_at)` удаляется, тот же запрос выполняется на `(created_at, user_id)`. Разница измерена: 7 buffers / 0,04 мс против 489 buffers / 2,0 мс.
- **Planner отказывается от индекса.** Агрегат за 400 дней идёт Seq Scan; `SET enable_seqscan = off` показывает индексный вариант с 11 438 buffers против 9 339 - при почти одинаковом времени на прогретом cache. Хороший повод объяснить, почему сравнивать нужно buffers.

Добавлен `special-indexes.sql` на миллион строк append-only лога: expression index (~53 мс -> ~0,5 мс на `lower(email)`), GIN `jsonb_path_ops` (не спасает неселективный `channel`, выигрывает на селективном `merchant`) и BRIN на коррелированной `created_at` - **32 kB против 21 MB** у B-tree при сопоставимом времени, с видимыми в плане `Heap Blocks: lossy` и `Rows Removed by Index Recheck`. Финальный запрос печатает `correlation` из `pg_stats` - условие, при котором BRIN вообще работает.

Остаётся учебной работой: сводная таблица «запрос -> план до -> индекс -> план после -> цена записи» и эксперимент с падением корреляции после массового UPDATE.
