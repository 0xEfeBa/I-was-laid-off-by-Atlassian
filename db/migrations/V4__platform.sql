CREATE TABLE platform.tenants (
    id             uuid        PRIMARY KEY,
    name           text        NOT NULL UNIQUE CHECK (name ~ '^[a-z][a-z0-9-]{1,30}$'),
    owner_id       uuid        NOT NULL,
    system         boolean     NOT NULL DEFAULT false,
    home_region    text        NOT NULL CHECK (home_region IN ('eu', 'us', 'ap')),
    active_version integer,
    deleted_at     timestamptz,
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ON platform.tenants (owner_id);

CREATE TABLE platform.tenant_versions (
    tenant_id  uuid        NOT NULL REFERENCES platform.tenants (id),
    version    integer     NOT NULL CHECK (version > 0),
    spec       jsonb       NOT NULL,
    spec_hash  text        NOT NULL,
    created_by uuid        NOT NULL,
    reason     text        NOT NULL DEFAULT 'APPLY' CHECK (reason IN ('APPLY', 'ROLLBACK')),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, version)
);

CREATE FUNCTION platform.reject_tenant_version_change() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'tenant versions are immutable';
END;
$$;

CREATE TRIGGER tenant_versions_immutable
    BEFORE UPDATE OR DELETE ON platform.tenant_versions
    FOR EACH ROW EXECUTE FUNCTION platform.reject_tenant_version_change();

CREATE TABLE platform.service_instances (
    instance_id     text        PRIMARY KEY,
    tenant_id       uuid        NOT NULL UNIQUE REFERENCES platform.tenants (id),
    service_id      text        NOT NULL,
    plan_id         text        NOT NULL,
    parameters_hash text        NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now()
);
