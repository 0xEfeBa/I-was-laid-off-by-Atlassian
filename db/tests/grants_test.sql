\set ON_ERROR_STOP on

CREATE FUNCTION pg_temp.check_access(role_name text, statement text, allowed boolean) RETURNS void
    LANGUAGE plpgsql AS $$
BEGIN
    EXECUTE format('SET LOCAL ROLE %I', role_name);
    BEGIN
        EXECUTE statement;
        IF NOT allowed THEN
            RAISE EXCEPTION '% must not be able to run: %', role_name, statement;
        END IF;
    EXCEPTION WHEN insufficient_privilege THEN
        IF allowed THEN
            RAISE EXCEPTION '% must be able to run: %', role_name, statement;
        END IF;
    END;
    RESET ROLE;
END;
$$;

SELECT pg_temp.check_access('identity_service', 'SELECT password_hash FROM identity.users', true);
SELECT pg_temp.check_access('identity_service',
    $$INSERT INTO identity.outbox (aggregate_type, aggregate_id, event_type, payload) VALUES ('t', 't', 't', '{}')$$, true);
SELECT pg_temp.check_access('identity_service', 'SELECT 1 FROM platform.tenants', false);

SELECT pg_temp.check_access('edge_sidecar', 'SELECT id, username, banned_at FROM identity.users', true);
SELECT pg_temp.check_access('edge_sidecar', 'SELECT token_hash, scopes FROM identity.personal_access_tokens', true);
SELECT pg_temp.check_access('edge_sidecar', 'SELECT id FROM identity.outbox', true);
SELECT pg_temp.check_access('edge_sidecar', 'SELECT password_hash FROM identity.users', false);
SELECT pg_temp.check_access('edge_sidecar', 'SELECT token_hash FROM identity.refresh_tokens', false);
SELECT pg_temp.check_access('edge_sidecar', $$UPDATE identity.users SET banned_at = NULL$$, false);

SELECT pg_temp.check_access('broker',
    $$INSERT INTO platform.outbox (aggregate_type, aggregate_id, event_type, payload) VALUES ('t', 't', 't', '{}')$$, true);
SELECT pg_temp.check_access('broker', 'SELECT 1 FROM platform.job_queue', true);
SELECT pg_temp.check_access('broker', 'SELECT 1 FROM identity.users', false);
SELECT pg_temp.check_access('broker', $$UPDATE platform.tenant_version_status SET detail = NULL WHERE false$$, true);
SELECT pg_temp.check_access('broker', $$UPDATE platform.service_instances SET operation_state = 'failed' WHERE false$$, true);

SELECT pg_temp.check_access('control_plane', 'SELECT spec FROM platform.tenant_versions', true);
SELECT pg_temp.check_access('control_plane', $$DELETE FROM platform.tenants$$, false);
SELECT pg_temp.check_access('control_plane', 'SELECT state FROM platform.tenant_version_status', true);
SELECT pg_temp.check_access('control_plane', $$UPDATE platform.tenant_version_status SET detail = NULL WHERE false$$, false);

\echo 'All grant checks passed'
