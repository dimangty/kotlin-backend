# Неделя 5. Составные индексы и EXPLAIN

Эксперимент со skew: только около 2% платежей имеют `PENDING`.

Для каждого запроса сохраните: SQL, распределение данных, план до, индекс, план после, buffers, размер индекса и влияние на массовый INSERT. Отдельно сравните `(user_id, created_at)` с `(created_at, user_id)`.

```bash
docker compose up -d
docker compose exec postgres psql -U study -d plans
```

