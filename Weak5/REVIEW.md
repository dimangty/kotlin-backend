# Review

Статус: лаборатория повторно прошла на 1 000 000 payments; PENDING ≈2%. Covering и partial indexes дали Index Only Scan с `Heap Fetches: 0`, а history query ускорился примерно с 18,5 мс до 0,06 мс.

Наблюдение: history query выбрал Bitmap Heap Scan + sort, а не слепо Index Scan — это полезный пример того, что planner учитывает cost/statistics.
