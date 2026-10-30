CREATE TABLE identity.outbox (
    id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    txid           xid8        NOT NULL DEFAULT pg_current_xact_id(),
    aggregate_type text        NOT NULL,
    aggregate_id   text        NOT NULL,
    event_type     text        NOT NULL,
    payload        jsonb       NOT NULL,
    created_at     timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ON identity.outbox (txid, id);

CREATE TABLE platform.outbox (LIKE identity.outbox INCLUDING ALL);

CREATE TABLE platform.job_queue (
    id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    queue        text        NOT NULL,
    dedup_key    text,
    payload      jsonb       NOT NULL,
    status       text        NOT NULL DEFAULT 'PENDING'
                 CHECK (status IN ('PENDING', 'RUNNING', 'DONE', 'FAILED')),
    attempts     integer     NOT NULL DEFAULT 0,
    max_attempts integer     NOT NULL DEFAULT 5,
    run_after    timestamptz NOT NULL DEFAULT now(),
    locked_until timestamptz,
    last_error   text,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ON platform.job_queue (queue, dedup_key) WHERE dedup_key IS NOT NULL;
CREATE INDEX ON platform.job_queue (queue, run_after) WHERE status IN ('PENDING', 'RUNNING');
