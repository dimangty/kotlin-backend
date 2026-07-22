# Неделя 3. PostgreSQL: схема, SQL и физическое хранение

SQL-first лаборатория финтех-схемы. Ограничения защищают простые инварианты без участия Kotlin-кода.

```bash
docker compose up -d
docker compose exec postgres psql -U study -d fintech
```

В `psql` исследуйте `ctid`, `xmin`, `pg_relation_size`, затем обновите строку и выполните `VACUUM (VERBOSE, ANALYZE) payments`.

## Задания

1. Дописать `queries.sql` до 20 содержательных запросов.
2. Объяснить каждое PK/FK/UNIQUE/CHECK/NOT NULL.
3. Сгенерировать ledger entries и проверить равенство projection balance.
4. Сравнить logical row и physical tuple через `ctid`/`xmin`.

