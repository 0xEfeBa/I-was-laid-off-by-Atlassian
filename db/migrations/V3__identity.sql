CREATE TABLE identity.users (
    id            uuid        PRIMARY KEY,
    username      text        NOT NULL UNIQUE CHECK (username ~ '^[a-z0-9][a-z0-9-]{1,38}$'),
    email         text        NOT NULL UNIQUE CHECK (email = lower(email)),
    password_hash text        NOT NULL,
    role          text        NOT NULL DEFAULT 'USER' CHECK (role IN ('USER', 'ADMIN')),
    home_region   text        NOT NULL CHECK (home_region IN ('eu', 'us', 'ap')),
    banned_at     timestamptz,
    version       bigint      NOT NULL DEFAULT 1,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE identity.personal_access_tokens (
    id           uuid        PRIMARY KEY,
    user_id      uuid        NOT NULL REFERENCES identity.users (id) ON DELETE CASCADE,
    name         text        NOT NULL CHECK (length(name) BETWEEN 1 AND 100),
    token_hash   bytea       NOT NULL UNIQUE,
    scopes       text[]      NOT NULL CHECK (
                     cardinality(scopes) > 0
                     AND scopes <@ ARRAY['edge:read', 'edge:write', 'git:read', 'git:write']
                 ),
    expires_at   timestamptz,
    last_used_at timestamptz,
    revoked_at   timestamptz,
    created_at   timestamptz NOT NULL DEFAULT now(),
    UNIQUE (user_id, name)
);

CREATE TABLE identity.refresh_tokens (
    id         uuid        PRIMARY KEY,
    user_id    uuid        NOT NULL REFERENCES identity.users (id) ON DELETE CASCADE,
    family_id  uuid        NOT NULL,
    token_hash bytea       NOT NULL UNIQUE,
    expires_at timestamptz NOT NULL,
    used_at    timestamptz,
    revoked_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ON identity.refresh_tokens (family_id);
CREATE INDEX ON identity.refresh_tokens (user_id);

CREATE TABLE identity.login_attempts (
    username        text        PRIMARY KEY,
    failures        integer     NOT NULL DEFAULT 0,
    locked_until    timestamptz,
    last_failure_at timestamptz
);
