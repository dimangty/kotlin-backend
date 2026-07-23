# Углублённый трек: индексы

Обязательное дополнение к неделям 3-5. Задача трека - причинное понимание, а не набор правил для собеседования. Этот файл связывает семь обязательных лабораторных работ из плана с конкретными скриптами репозитория.

## Что нужно понимать

| Блок | Содержание | Где отрабатывается |
|---|---|---|
| Физический уровень | heap pages, tuples, `ctid`, page cache, random/sequential I/O, bloat, VACUUM, visibility map | `Weak3/sql/queries.sql` блок D, `Weak3/ledger.sql` |
| Структура B-tree | root/internal/leaf pages, отсортированные ключи, TID, traversal, page split, высота дерева | `Weak4/lab.sql` |
| Планировщик | statistics, cardinality, selectivity, correlation, cost model, estimated против actual rows | `Weak5/lab.sql` |
| Виды scan | Seq Scan, Index Scan, Bitmap Index/Heap Scan, Index Only Scan | `Weak5/lab.sql`, `Weak5/special-indexes.sql` |
| Составные индексы | порядок колонок, equality/range/sort, ведущие колонки | `Weak5/lab.sql`, секция "Порядок колонок" |
| Дополнительные формы | `INCLUDE`, partial, expression, unique, GIN, GiST, BRIN | `Weak5/special-indexes.sql` |
| Цена индекса | INSERT/UPDATE/DELETE overhead, место, WAL, cache pressure, reindex | `Weak4/write-overhead.sql`, `Weak8/migration-lab.sql` блок 5 (`CREATE INDEX` против `CONCURRENTLY`) |
| Поиск кандидата на индекс | `pg_stat_statements`, отбор запроса по суммарному времени, подтверждение по buffers | `Weak12/slow-query-lab.sql` |

## Семь обязательных лабораторных

- [ ] **1. Миллион строк с реалистичным распределением.** `Weak4/lab.sql` (1 000 000 events), `Weak5/lab.sql` (1 000 000 payments со skew: 2% `PENDING`).
- [ ] **2. Пять запросов с `EXPLAIN (ANALYZE, BUFFERS)` до и после индекса.** Планы нужно **сохранить как файлы** в отчёт недели, а не только посмотреть в консоли. Это единственный пункт, который скрипты за вас не сделают.
- [ ] **3. Planner сознательно не использует существующий индекс.** Два разных случая: низкая селективность (`status = 'DONE'` в `Weak4/lab.sql`) и слишком широкий диапазон (агрегат за 400 дней в `Weak5/lab.sql`, там же сравнение через `SET enable_seqscan = off`).
- [ ] **4. Влияние порядка колонок.** `Weak5/lab.sql`, секция "Порядок колонок": один и тот же запрос на `(user_id, created_at)` и `(created_at, user_id)`, разница примерно в 70 раз по buffers.
- [ ] **5. Обычный, partial и covering индекс.** `Weak5/lab.sql`: `payments_user_created_idx` (covering, `INCLUDE`), `payments_pending_idx` (partial), `payments_created_user_idx` (обычный составной).
- [ ] **6. Индекс на поле низкой cardinality.** `Weak4/lab.sql` (`status` в B-tree) и `Weak5/special-indexes.sql` (`channel` в GIN). Оба раза индекс не спасает - вывод один и тот же для разных типов индексов.
- [ ] **7. Write overhead.** `Weak4/write-overhead.sql`: массовый INSERT в таблицы с нулём, одним и четырьмя индексами, плюс замер WAL и `n_tup_hot_upd`.

## Форма ответа

Ответ "какой индекс нужен" не принимается. Для каждого индекса в отчёте должно быть:

1. точный SQL-запрос, который оптимизируется;
2. распределение данных и ожидаемая selectivity;
3. используемые predicates и ordering;
4. план до и план после, с числом прочитанных buffers;
5. цена: время массового INSERT, объём WAL и размер индекса на диске.

Пункты 4 и 5 - это ровно то, что печатают `EXPLAIN (ANALYZE, BUFFERS)` и `Weak4/write-overhead.sql`.

## Типовые ошибки трека

- Сравнивать планы по `Execution Time` на прогретом cache. Сравнивать нужно по `Buffers`: время зависит от того, что уже лежит в памяти.
- Мерить индекс на десяти строках. На маленькой таблице Seq Scan выигрывает всегда, и никакого вывода из этого сделать нельзя.
- Забыть `ANALYZE` после массовой вставки и удивляться выбору плана.
- Использовать `gen_random_uuid()` прямо в predicate: функция VOLATILE, значение будет новым для каждой строки.
- Оставить `SET enable_seqscan = off` где-нибудь, кроме диагностики.
- Завести индекс, для которого нельзя назвать запрос-выгодоприобретатель. Такой индекс оплачивается каждым INSERT и не возвращает ничего.

## Материалы

- [Chapter 11. Indexes](https://www.postgresql.org/docs/17/indexes.html)
- [B-Tree Indexes](https://www.postgresql.org/docs/17/btree.html)
- [Multicolumn Indexes](https://www.postgresql.org/docs/17/indexes-multicolumn.html)
- [Index-Only Scans and Covering Indexes](https://www.postgresql.org/docs/17/indexes-index-only-scans.html)
- [Using EXPLAIN](https://www.postgresql.org/docs/17/using-explain.html)
