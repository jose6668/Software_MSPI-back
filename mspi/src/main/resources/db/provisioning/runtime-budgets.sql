\set ON_ERROR_STOP on
-- Existing dedicated MSPI database only. Provisioning operator; no passwords or schema changes.
BEGIN;
DO $budgets$
DECLARE runtime text;
BEGIN
    FOR runtime IN SELECT rolname FROM pg_catalog.pg_roles
        WHERE rolname = 'mspi_common_runtime' OR rolname IN
            (SELECT 'mspi_run_' || replace(id::text, '-', '') FROM comun.empresas)
    LOOP
        EXECUTE format('ALTER ROLE %I CONNECTION LIMIT %s', runtime,
            CASE WHEN runtime = 'mspi_common_runtime' THEN 12 ELSE 4 END);
        EXECUTE format('ALTER ROLE %I IN DATABASE %I SET statement_timeout = ''5s''', runtime, current_database());
        EXECUTE format('ALTER ROLE %I IN DATABASE %I SET lock_timeout = ''1s''', runtime, current_database());
        EXECUTE format('ALTER ROLE %I IN DATABASE %I SET idle_in_transaction_session_timeout = ''5s''', runtime, current_database());
        EXECUTE format('ALTER ROLE %I IN DATABASE %I SET transaction_timeout = ''15s''', runtime, current_database());
        EXECUTE format('ALTER ROLE %I IN DATABASE %I SET work_mem = ''4MB''', runtime, current_database());
        EXECUTE format('ALTER ROLE %I IN DATABASE %I SET temp_file_limit = ''64MB''', runtime, current_database());
        EXECUTE format('ALTER ROLE %I IN DATABASE %I SET max_parallel_workers_per_gather = ''0''', runtime, current_database());
    END LOOP;
END
$budgets$;
COMMIT;
