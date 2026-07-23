-- Неделя 8. Лаборатория миграций: expand-contract и NOT NULL на большой таблице.
--
-- Запуск (одна сессия, всё воспроизводится автоматически):
--   docker compose up -d
--   docker compose exec -T postgres psql -U study -d data -v ON_ERROR_STOP=1 < migration-lab.sql
--
-- Задача лаборатории - увидеть своими глазами, какие ALTER TABLE переписывают
-- таблицу целиком под ACCESS EXCLUSIVE, какие только читают её, а какие вообще
-- меняют один байт в каталоге. Пока это не измерено, "безопасная миграция"
-- остаётся словом из чеклиста.

\timing on
\set ON_ERROR_STOP on

-- ============================================================
-- Блок 0. Таблица размером, на котором разница видна
-- ============================================================

DROP TABLE IF EXISTS migration_payments;

CREATE TABLE migration_payments(
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    account_id    uuid NOT NULL,
    amount_minor  bigint NOT NULL CHECK (amount_minor > 0),
    status        text NOT NULL,
    created_at    timestamptz NOT NULL
);

INSERT INTO migration_payments(account_id, amount_minor, status, created_at)
SELECT
    ('00000000-0000-0000-0000-' || lpad(((i % 5000))::text, 12, '0'))::uuid,
    100 + (i % 90000),
    (ARRAY['PENDING','COMPLETED','FAILED'])[1 + (i % 3)],
    now() - make_interval(secs => i % 15552000)
FROM generate_series(1, 500000) AS s(i);

ANALYZE migration_payments;

SELECT pg_size_pretty(pg_total_relation_size('migration_payments')) AS total_size;

-- ============================================================
-- Блок 1. ADD COLUMN: что переписывает таблицу, а что нет
-- ============================================================

-- 1.1 Нельзя просто так добавить NOT NULL колонку без значения:
--     существующие 500 000 строк нечем заполнить.
DO $$
BEGIN
    ALTER TABLE migration_payments ADD COLUMN currency_bad char(3) NOT NULL;
    RAISE EXCEPTION 'ожидалась ошибка 23502, миграция не должна была пройти';
EXCEPTION
    WHEN not_null_violation THEN
        RAISE NOTICE '1.1 ожидаемо отклонено: %', SQLERRM;
END
$$;

-- 1.2 ADD COLUMN ... DEFAULT: начиная с PostgreSQL 11 значение по умолчанию
--     хранится в каталоге (pg_attribute.atthasmissing), строки не переписываются.
--     Сравните время с блоком 3: это доли секунды на любой таблице.
ALTER TABLE migration_payments ADD COLUMN currency char(3) NOT NULL DEFAULT 'EUR';

SELECT attname, atthasmissing, attmissingval
FROM pg_attribute
WHERE attrelid = 'migration_payments'::regclass AND attname = 'currency';

-- 1.3 Колонка релиза "expand": nullable, без default, приложение пока её не требует.
ALTER TABLE migration_payments ADD COLUMN external_ref text;

-- ============================================================
-- Блок 2. Опасный путь: SET NOT NULL одним движением
-- ============================================================

-- Заполняем колонку так, как это сделал бы бэкофилл.
UPDATE migration_payments SET external_ref = 'legacy-' || id::text;

-- 2.1 SET NOT NULL берёт ACCESS EXCLUSIVE и читает всю таблицу целиком.
--     На 500k строк это доли секунды, на 500 млн - минуты полной недоступности:
--     на этой блокировке встают в очередь даже SELECT.
ALTER TABLE migration_payments ALTER COLUMN external_ref SET NOT NULL;

-- Возвращаем состояние "до" для честного сравнения в блоке 3.
ALTER TABLE migration_payments ALTER COLUMN external_ref DROP NOT NULL;

-- ============================================================
-- Блок 3. Безопасный путь: NOT VALID -> VALIDATE -> SET NOT NULL
-- ============================================================

-- 3.1 CHECK ... NOT VALID добавляется мгновенно: существующие строки не читаются,
--     но новые и изменённые строки проверяются уже с этого момента.
ALTER TABLE migration_payments
    ADD CONSTRAINT migration_payments_external_ref_not_null
    CHECK (external_ref IS NOT NULL) NOT VALID;

SELECT conname, convalidated
FROM pg_constraint
WHERE conrelid = 'migration_payments'::regclass
  AND conname = 'migration_payments_external_ref_not_null';

-- 3.2 VALIDATE CONSTRAINT читает таблицу под SHARE UPDATE EXCLUSIVE:
--     чтение и запись продолжают работать, блокируются только DDL и VACUUM FULL.
ALTER TABLE migration_payments
    VALIDATE CONSTRAINT migration_payments_external_ref_not_null;

-- 3.3 Начиная с PostgreSQL 12 планировщик миграции использует валидный CHECK
--     как доказательство и не сканирует таблицу второй раз. ACCESS EXCLUSIVE
--     берётся, но удерживается на время каталогической операции, а не на время
--     полного скана.
ALTER TABLE migration_payments ALTER COLUMN external_ref SET NOT NULL;

-- 3.4 После этого дублирующий CHECK не нужен: NOT NULL сам является гарантией.
ALTER TABLE migration_payments
    DROP CONSTRAINT migration_payments_external_ref_not_null;

-- ============================================================
-- Блок 4. Что переписывает таблицу: смотрим на relfilenode
-- ============================================================
-- relfilenode - это имя файла на диске. Если после ALTER оно изменилось,
-- PostgreSQL переписал таблицу целиком и на это время держал ACCESS EXCLUSIVE.

CREATE TEMP TABLE rewrite_probe(n serial, step text, relfilenode oid);

INSERT INTO rewrite_probe(step, relfilenode)
SELECT 'исходная таблица', relfilenode FROM pg_class WHERE oid = 'migration_payments'::regclass;

-- 4.1 text -> varchar(32): длина не проверялась раньше, поэтому база обязана
--     прочитать все строки; изменение считается сменой типа и даёт rewrite.
ALTER TABLE migration_payments ALTER COLUMN status TYPE varchar(32);

INSERT INTO rewrite_probe(step, relfilenode)
SELECT 'text -> varchar(32)', relfilenode FROM pg_class WHERE oid = 'migration_payments'::regclass;

-- 4.2 varchar(32) -> varchar(64): ограничение только ослабляется, проверять
--     нечего. Это единственный из трёх ALTER, который стоит почти ноль.
ALTER TABLE migration_payments ALTER COLUMN status TYPE varchar(64);

INSERT INTO rewrite_probe(step, relfilenode)
SELECT 'varchar(32) -> varchar(64)', relfilenode FROM pg_class WHERE oid = 'migration_payments'::regclass;

-- 4.3 bigint -> numeric: физическое представление другое, таблица переписывается.
ALTER TABLE migration_payments ALTER COLUMN amount_minor TYPE numeric(20, 0);

INSERT INTO rewrite_probe(step, relfilenode)
SELECT 'bigint -> numeric', relfilenode FROM pg_class WHERE oid = 'migration_payments'::regclass;

SELECT
    step,
    relfilenode,
    coalesce(relfilenode <> lag(relfilenode) OVER (ORDER BY n), false) AS rewritten
FROM rewrite_probe
ORDER BY n;

-- ============================================================
-- Блок 5. Индекс без остановки записи
-- ============================================================

-- 5.1 Обычный CREATE INDEX блокирует запись в таблицу на всё время построения.
CREATE INDEX migration_payments_status_idx ON migration_payments(status);
DROP INDEX migration_payments_status_idx;

-- 5.2 CONCURRENTLY не блокирует запись, но делает два прохода, работает дольше
--     и не может выполняться внутри транзакции. При падении остаётся INVALID
--     индекс, который нужно удалить руками - это цена онлайн-построения.
CREATE INDEX CONCURRENTLY migration_payments_status_idx ON migration_payments(status);

SELECT indexrelid::regclass AS index_name, indisvalid, indisready
FROM pg_index
WHERE indrelid = 'migration_payments'::regclass
  AND indexrelid = 'migration_payments_status_idx'::regclass;

-- ============================================================
-- Блок 6. Бэкофилл батчами вместо одного большого UPDATE
-- ============================================================
-- Один UPDATE на 500k строк - это одна длинная транзакция: она держит snapshot,
-- мешает VACUUM и генерирует один большой кусок WAL. Батчи дают короткие
-- транзакции, которые можно останавливать и возобновлять.

ALTER TABLE migration_payments ADD COLUMN settled_at timestamptz;

DO $$
DECLARE
    updated integer;
    batches integer := 0;
BEGIN
    LOOP
        UPDATE migration_payments
        SET settled_at = created_at + interval '1 hour'
        WHERE id IN (
            SELECT id FROM migration_payments
            WHERE settled_at IS NULL AND status = 'COMPLETED'
            LIMIT 20000
        );
        GET DIAGNOSTICS updated = ROW_COUNT;
        batches := batches + 1;
        EXIT WHEN updated = 0;
    END LOOP;
    RAISE NOTICE '6. бэкофилл завершён за % батчей', batches;
END
$$;

-- Цена бэкофилла: каждая обновлённая строка - это новая версия строки.
SELECT n_live_tup, n_dead_tup
FROM pg_stat_user_tables
WHERE relname = 'migration_payments';

VACUUM (ANALYZE) migration_payments;

SELECT n_live_tup, n_dead_tup
FROM pg_stat_user_tables
WHERE relname = 'migration_payments';

-- ============================================================
-- Блок 7. lock_timeout как страховка деплоя
-- ============================================================
-- Миграция, которая не может взять блокировку, должна быстро сдаться,
-- а не выстраивать за собой очередь из запросов приложения.

SET lock_timeout = '3s';

BEGIN;
    ALTER TABLE migration_payments ADD COLUMN deploy_probe boolean;
    ALTER TABLE migration_payments DROP COLUMN deploy_probe;
COMMIT;

RESET lock_timeout;

-- Очередь блокировок в момент миграции (запускать из третьего окна, пока
-- ALTER висит; при пустой очереди возвращает ноль строк):
SELECT
    a.pid,
    a.state,
    a.wait_event_type,
    left(a.query, 60) AS query,
    pg_blocking_pids(a.pid) AS blocked_by
FROM pg_stat_activity a
WHERE a.datname = current_database()
  AND a.pid <> pg_backend_pid()
  AND cardinality(pg_blocking_pids(a.pid)) > 0;

\timing off
