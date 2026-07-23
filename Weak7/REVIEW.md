# Review

Статус: Testcontainers integration tests подтверждают конкурентный retry: два одновременных запроса возвращают один transfer ID; balances 900/1100, transfers=1, ledger entries=2. Повтор ключа с другим payload отклоняется. Отдельный прогон 50 встречных переводов сохраняет total balance и zero-sum ledger.

Сильные стороны: ordered row locks, `INSERT ... ON CONFLICT`, повторная проверка после lock, bounded idempotency key и DB constraints.

Добавлена SQL-лаборатория блокировок (`locks-setup.sql`, `locks-session-a.sql`, `locks-session-b.sql`, `locks-inspect.sql`) - неделя больше не начинается сразу с приложения. Все четыре файла проверены на PostgreSQL 17 двумя параллельными сессиями:

- lock wait без цикла: `pg_blocking_pids()` возвращает блокирующий PID, `pg_locks` показывает `granted = false`;
- deadlock воспроизводится стабильно и даёт ровно ожидаемое `SQLSTATE 40P01` с обоими направлениями wait-for cycle в `DETAIL`;
- тот же перевод с `ORDER BY id FOR UPDATE` вырождается в обычное ожидание, сумма не меняется;
- `SKIP LOCKED` разводит два worker'а по непересекающимся заданиям, без него - очередь.

`locks-setup.sql` задаёт роли `lock_timeout = '10s'`, чтобы зависшая сессия падала с `55P03`, а не висела. Счётчик `pg_stat_database.deadlocks` читается после `pg_stat_force_next_flush()` - без этого он отстаёт и показывает ноль сразу после эксперимента.

Остаётся учебной работой: bounded retry для `40P01`/`40001` в самом сервисе и обработчик очереди на `SKIP LOCKED` поверх таблицы `jobs`.
