-- Отдельная схема гарантирует, что учебное приложение не изменит пользовательские таблицы.
CREATE SCHEMA IF NOT EXISTS dbapp_lab;
SET search_path TO dbapp_lab, public;

-- Счета демонстрируют перевод, ограничения, row locks и optimistic locking.
CREATE TABLE IF NOT EXISTS accounts (
    id       bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    owner    text NOT NULL UNIQUE,
    balance  numeric(12, 2) NOT NULL CHECK (balance >= 0),
    version  integer NOT NULL DEFAULT 0
);

-- Начальные строки добавляются только один раз; сценарии затем возвращают известные балансы.
INSERT INTO accounts(owner, balance)
VALUES ('Alice', 1000), ('Bob', 1000), ('Carol', 500)
ON CONFLICT (owner) DO NOTHING;

-- Два врача образуют общий инвариант «хотя бы один остаётся дежурным».
CREATE TABLE IF NOT EXISTS doctors (
    id       bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name     text NOT NULL UNIQUE,
    on_call  boolean NOT NULL DEFAULT true
);

INSERT INTO doctors(name, on_call)
VALUES ('Иван', true), ('Мария', true)
ON CONFLICT (name) DO NOTHING;

-- Большая таблица остаётся пустой до первого индексного эксперимента.
CREATE TABLE IF NOT EXISTS orders (
    id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    customer_id  bigint NOT NULL,
    status       text NOT NULL CHECK (status IN ('new', 'paid', 'cancelled')),
    email        text NOT NULL,
    total        numeric(12, 2) NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    payload      jsonb NOT NULL DEFAULT '{}'::jsonb
);

-- Очередь нужна для атомарного паттерна FOR UPDATE SKIP LOCKED.
CREATE TABLE IF NOT EXISTS jobs (
    id       bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    status   text NOT NULL DEFAULT 'queued',
    payload  jsonb NOT NULL
);

-- Начальная очередь полезна при первом запуске, а каждый сценарий всё равно пересоздаёт её.
INSERT INTO jobs(status, payload)
SELECT seed.status, seed.payload
FROM (
    VALUES
        ('queued', '{"task":"первая"}'::jsonb),
        ('queued', '{"task":"вторая"}'::jsonb),
        ('queued', '{"task":"третья"}'::jsonb)
) AS seed(status, payload)
WHERE NOT EXISTS (SELECT 1 FROM jobs);
