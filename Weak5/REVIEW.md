# Review

Статус: лаборатория прошла на 1 000 000 payments; PENDING ≈2%. Проверены composite, reversed, partial и covering indexes реальным `EXPLAIN (ANALYZE, BUFFERS)`.

Наблюдение: history query выбрал Bitmap Heap Scan + sort, а не слепо Index Scan — это полезный пример того, что planner учитывает cost/statistics.

