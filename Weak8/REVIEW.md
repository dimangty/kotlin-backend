# Review

Статус: Testcontainers-тест проходит с PostgreSQL 17. Flyway миграция применяется, Hibernate `validate` проходит, JPA dirty checking меняет status, а JDBC projection возвращает daily total. Testcontainers зафиксирован на 2.0.5 для совместимости с текущим Docker Engine.

Добавлен `migration-lab.sql` - неделя больше не обсуждает безопасные миграции только на словах. Скрипт прогнан на PostgreSQL 17 с `ON_ERROR_STOP=1` на таблице в 500 000 строк (60 MB):

- прямой `ALTER COLUMN ... SET NOT NULL` - 40 мс полного скана под ACCESS EXCLUSIVE;
- `CHECK ... NOT VALID` (0.4 мс) -> `VALIDATE CONSTRAINT` (17 мс, SHARE UPDATE EXCLUSIVE) -> `SET NOT NULL` (0.4 мс): окно жёсткой блокировки сокращается в сто раз;
- `ADD COLUMN ... DEFAULT` не переписывает строки - значение видно в `pg_attribute.attmissingval`;
- rewrite проверяется по `relfilenode`: `text -> varchar(32)` переписывает файл, `varchar(32) -> varchar(64)` нет, `bigint -> numeric` переписывает;
- `CREATE INDEX` 107 мс с блокировкой записи против `CONCURRENTLY` 146 мс без неё;
- бэкофилл 10 батчами, после него `VACUUM (ANALYZE)` обнуляет `n_dead_tup`.

Ограничение: это data-layer slice без controller; N+1 experiment и HTTP-слой остаются заданиями.
