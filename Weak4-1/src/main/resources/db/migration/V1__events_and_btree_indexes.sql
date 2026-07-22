CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE events (
    -- PRIMARY KEY автоматически создаёт уникальный B-tree для последовательного bigint.
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    -- UNIQUE создаёт ещё один B-tree: высокая selectivity делает UUID lookup дешёвым.
    public_id uuid NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    user_id bigint NOT NULL,
    status text NOT NULL CHECK (status IN ('NEW', 'DONE', 'FAILED')),
    created_at timestamptz NOT NULL DEFAULT now()
);

-- Монотонный timestamp обычно хорошо коррелирует с физическим порядком вставки.
CREATE INDEX events_created_at_idx ON events(created_at);

-- Индекс на трёх статусах намеренно оставлен для эксперимента: planner часто выберет
-- Seq Scan, потому что чтение большой доли heap через индекс дороже последовательного чтения.
CREATE INDEX events_status_idx ON events(status);
