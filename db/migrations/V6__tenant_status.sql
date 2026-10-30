ALTER TABLE platform.tenants DROP CONSTRAINT tenants_name_key;
CREATE UNIQUE INDEX tenants_live_name ON platform.tenants (name) WHERE deleted_at IS NULL;

CREATE TABLE platform.tenant_version_status (
    tenant_id  uuid        NOT NULL,
    version    integer     NOT NULL,
    state      text        NOT NULL CHECK (state IN ('VALIDATING', 'ACTIVE', 'REJECTED', 'SUPERSEDED')),
    detail     text,
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, version),
    FOREIGN KEY (tenant_id, version) REFERENCES platform.tenant_versions (tenant_id, version)
);

ALTER TABLE platform.service_instances
    ADD COLUMN operation             text        NOT NULL DEFAULT 'provision'
        CHECK (operation IN ('provision', 'update', 'deprovision')),
    ADD COLUMN operation_state       text        NOT NULL DEFAULT 'in progress'
        CHECK (operation_state IN ('in progress', 'succeeded', 'failed')),
    ADD COLUMN operation_description text,
    ADD COLUMN updated_at            timestamptz NOT NULL DEFAULT now();
