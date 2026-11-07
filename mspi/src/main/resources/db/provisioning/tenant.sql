\set ON_ERROR_STOP on
-- Required psql variable: empresa_id. Keep the lock key aligned with TenantMigrationLock.
BEGIN;
SELECT pg_advisory_xact_lock(1297305673, 5);
SELECT set_config('mspi.preparation_company', (:'empresa_id'::uuid)::text, true);
\ir tenant-bootstrap.sql
COMMIT;
