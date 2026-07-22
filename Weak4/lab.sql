CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE TABLE events (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    public_id uuid NOT NULL DEFAULT gen_random_uuid(),
    user_id bigint NOT NULL,
    status text NOT NULL,
    created_at timestamptz NOT NULL
);

INSERT INTO events(user_id, status, created_at)
SELECT 1 + (random() * 99999)::bigint,
       (ARRAY['NEW','DONE','FAILED'])[1 + (random() * 2)::int],
       now() - random() * interval '365 days'
FROM generate_series(1, 1000000);
ANALYZE events;

-- Сначала сохраните план без индекса, затем повторите после CREATE INDEX.
EXPLAIN (ANALYZE, BUFFERS) SELECT * FROM events WHERE public_id = gen_random_uuid();
CREATE INDEX events_public_id_idx ON events(public_id);

CREATE INDEX events_created_at_idx ON events(created_at);
CREATE INDEX events_status_idx ON events(status);
ANALYZE events;

-- Planner обычно предпочтет Seq Scan для низкоселективного predicate.
EXPLAIN (ANALYZE, BUFFERS) SELECT count(*) FROM events WHERE status = 'DONE';

SELECT pg_size_pretty(pg_relation_size('events')) AS heap,
       pg_size_pretty(pg_indexes_size('events')) AS all_indexes;

