# Неделя 6. MVCC, ACID и isolation

Откройте две сессии:

```bash
docker compose up -d
docker compose exec postgres psql -U study -d isolation
```

Выполняйте `session-a.sql` и `session-b.sql` по одному statement. Для каждого опыта нарисуйте timeline, snapshot, видимые версии и итоговый инвариант.

## Обязательные опыты

1. Non-repeatable read в Read Committed.
2. Lost update через read-compute-write, затем исправление atomic UPDATE и row lock.
3. Стабильный snapshot в Repeatable Read.
4. Serialization failure и ограниченный retry всей бизнес-транзакции.

