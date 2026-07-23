# Review

Статус: SQL schema запускается на PostgreSQL 17 ARM64. Проверено: 1 000 accounts, 100 000 payments, PK/FK/UNIQUE/CHECK применены.

`queries.sql` доведён до 20 запросов и разбит на блоки: согласованность денег, JOIN/GROUP BY/HAVING, CTE и window functions, физическое хранение (`ctid`, `xmin`, страницы, размеры, `n_dead_tup`) и инварианты уровня базы. Весь файл прогнан с `ON_ERROR_STOP=1`.

Добавлен `ledger.sql`: он создаёт immutable проводки и пересчитывает projection **из них**. До запуска запрос 2 показывает расхождение на всех 1 000 счетах, после - `drifted_accounts = 0` и `unposted_payments = 0`. Побочный эффект скрипта тоже учебный: `UPDATE` на 1 000 строк оставляет 1 000 dead tuples, и `VACUUM` в том же файле обнуляет их на глазах.

Остаётся учебной работой: `UNIQUE (payment_id)` в `idempotency_keys` (сейчас запрос 20 проверяет дубли вручную) и собственный замер bloat после массового UPDATE.
