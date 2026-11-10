DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'monitoring') THEN
        CREATE ROLE monitoring LOGIN;
    END IF;
END
$$;

GRANT pg_monitor TO monitoring;
