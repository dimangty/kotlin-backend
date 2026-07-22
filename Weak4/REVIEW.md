# Review

Статус: лаборатория повторно прошла на 1 000 000 events. Поиск существующего UUID сменил Parallel Seq Scan на Index Scan (примерно 18,3 мс -> 0,05 мс); для `status='DONE'` PostgreSQL обоснованно выбрал Parallel Seq Scan.

Следующий шаг: сохранить планы до/после и отдельно измерить INSERT overhead с нулём/одним/несколькими индексами.
