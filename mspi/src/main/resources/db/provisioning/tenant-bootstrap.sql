-- Run inside a transaction with the enterprise preparation lock and a local UUID setting.
DO $bootstrap$
DECLARE
    company uuid := current_setting('mspi.preparation_company')::uuid;
    suffix text := replace(company::text, '-', '');
    tenant_schema text := 'emp_' || suffix;
    migrator text := 'mspi_mig_' || suffix;
    runtime text := 'mspi_run_' || suffix;
    role_count integer;
    schema_owner text;
BEGIN
    PERFORM 1 FROM comun.empresas
     WHERE id = company AND esquema_interno = tenant_schema AND estado = 'EN_PREPARACION'
     FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'La empresa debe estar reservada y en preparación.';
    END IF;

    SELECT count(*) INTO role_count FROM pg_catalog.pg_roles WHERE rolname IN (migrator, runtime);
    SELECT pg_get_userbyid(nspowner) INTO schema_owner FROM pg_catalog.pg_namespace WHERE nspname = tenant_schema;

    IF role_count = 0 AND schema_owner IS NULL THEN
        EXECUTE format('CREATE ROLE %I LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS', migrator);
        EXECUTE format('CREATE ROLE %I LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS', runtime);
        EXECUTE format('GRANT CONNECT ON DATABASE %I TO %I, %I', current_database(), migrator, runtime);
        EXECUTE format('CREATE SCHEMA %I AUTHORIZATION %I', tenant_schema, migrator);
        EXECUTE format('REVOKE ALL ON SCHEMA %I FROM PUBLIC', tenant_schema);
        EXECUTE format('GRANT USAGE ON SCHEMA %I TO %I', tenant_schema, runtime);
        EXECUTE format('ALTER ROLE %I IN DATABASE %I SET search_path = %I', runtime, current_database(), tenant_schema);
        EXECUTE format('GRANT USAGE ON SCHEMA comun TO %I', migrator);
        EXECUTE format('GRANT REFERENCES (id) ON TABLE comun.empresas TO %I', migrator);
    ELSIF role_count <> 2 OR schema_owner IS DISTINCT FROM migrator THEN
        RAISE EXCEPTION 'Recursos de aprovisionamiento incompletos o en conflicto.';
    END IF;

    IF EXISTS (
        SELECT 1 FROM pg_catalog.pg_roles r
        WHERE r.rolname IN (migrator, runtime) AND (
            NOT r.rolcanlogin OR r.rolsuper OR r.rolcreatedb OR r.rolcreaterole OR r.rolinherit
            OR r.rolreplication OR r.rolbypassrls
            OR EXISTS (SELECT 1 FROM pg_catalog.pg_auth_members m WHERE m.member = r.oid)
            OR has_database_privilege(r.oid, current_database(), 'CREATE,TEMP')
            OR EXISTS (
                SELECT 1 FROM pg_catalog.pg_namespace n
                WHERE n.nspname <> tenant_schema
                  AND (has_schema_privilege(r.oid, n.oid, 'CREATE')
                       OR (starts_with(n.nspname, 'emp_') AND has_schema_privilege(r.oid, n.oid, 'USAGE')))
            )
        )
    ) OR NOT has_schema_privilege(runtime, tenant_schema, 'USAGE')
      OR has_schema_privilege(runtime, tenant_schema, 'CREATE')
      OR has_schema_privilege(runtime, 'comun', 'USAGE')
      OR has_table_privilege(migrator, 'comun.empresas', 'SELECT,INSERT,UPDATE,DELETE,TRUNCATE,TRIGGER')
      OR has_any_column_privilege(migrator, 'comun.empresas', 'SELECT,INSERT,UPDATE')
    THEN
        RAISE EXCEPTION 'Los roles empresariales exceden su ámbito o tienen permisos incompletos.';
    END IF;
END
$bootstrap$;
