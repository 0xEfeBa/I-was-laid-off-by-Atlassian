DO $$
DECLARE
    service text;
BEGIN
    FOREACH service IN ARRAY ARRAY[
        'identity_service', 'edge_sidecar', 'broker', 'control_plane'
    ] LOOP
        IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = service) THEN
            EXECUTE format('CREATE ROLE %I LOGIN', service);
        END IF;
    END LOOP;
END
$$;

REVOKE ALL ON SCHEMA public FROM PUBLIC;
REVOKE ALL ON SCHEMA identity, platform FROM PUBLIC;

GRANT USAGE ON SCHEMA identity TO identity_service, edge_sidecar;
GRANT USAGE ON SCHEMA platform TO broker, control_plane;

GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA identity TO identity_service;
ALTER DEFAULT PRIVILEGES IN SCHEMA identity
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO identity_service;

GRANT SELECT (id, username, role, home_region, banned_at, version) ON identity.users TO edge_sidecar;
GRANT SELECT (id, user_id, token_hash, scopes, expires_at, revoked_at)
    ON identity.personal_access_tokens TO edge_sidecar;
GRANT SELECT ON identity.outbox TO edge_sidecar;

GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA platform TO broker;
ALTER DEFAULT PRIVILEGES IN SCHEMA platform
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO broker;
GRANT SELECT ON ALL TABLES IN SCHEMA platform TO control_plane;
ALTER DEFAULT PRIVILEGES IN SCHEMA platform GRANT SELECT ON TABLES TO control_plane;
