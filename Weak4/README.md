# Неделя 4. B-tree изнутри

Лаборатория создаёт миллион строк и сравнивает bigint, UUID, timestamp и low-cardinality status.

```bash
docker compose up -d
docker compose exec postgres psql -U study -d indexes
```

Задание: выполнить `EXPLAIN (ANALYZE, BUFFERS)` до/после индексов, нарисовать root/internal/leaf traversal и записать цену индексов для INSERT. Для повторного эксперимента используйте `docker compose down -v`.

