\set ON_ERROR_STOP on
-- Required psql variable: empresa_id. Cast before deriving identifiers.
SELECT :'empresa_id'::uuid AS company_id,
       'emp_' || replace(:'empresa_id'::uuid::text, '-', '') AS tenant_schema,
       'mspi_mig_' || replace(:'empresa_id'::uuid::text, '-', '') AS migration_role,
       'mspi_run_' || replace(:'empresa_id'::uuid::text, '-', '') AS runtime_role
\gset

SELECT EXISTS (
    SELECT 1 FROM comun.empresas
    WHERE id = :'company_id'::uuid AND esquema_interno = :'tenant_schema'
      AND estado = 'EN_PREPARACION'
) AS company_reserved
\gset
\if :company_reserved
\else
    DO $$ BEGIN
        RAISE EXCEPTION 'La empresa debe estar reservada y en preparación.';
    END $$;
\endif

BEGIN;
SELECT format('CREATE ROLE %I LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS', :'migration_role') \gexec
SELECT format('CREATE ROLE %I LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS', :'runtime_role') \gexec
SELECT format('GRANT CONNECT ON DATABASE %I TO %I, %I', current_database(), :'migration_role', :'runtime_role') \gexec
SELECT format('CREATE SCHEMA %I AUTHORIZATION %I', :'tenant_schema', :'migration_role') \gexec
SELECT format('REVOKE ALL ON SCHEMA %I FROM PUBLIC', :'tenant_schema') \gexec
SELECT format('GRANT USAGE ON SCHEMA %I TO %I', :'tenant_schema', :'runtime_role') \gexec
SELECT format('ALTER ROLE %I IN DATABASE %I SET search_path = %I', :'runtime_role', current_database(), :'tenant_schema') \gexec

-- REFERENCES is sufficient for the owner FK; no common directory SELECT is granted.
SELECT format('GRANT USAGE ON SCHEMA comun TO %I', :'migration_role') \gexec
SELECT format('GRANT REFERENCES (id) ON TABLE comun.empresas TO %I', :'migration_role') \gexec
COMMIT;
