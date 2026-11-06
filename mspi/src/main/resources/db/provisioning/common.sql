\set ON_ERROR_STOP on
-- Initial bootstrap of a dedicated MSPI database, run by its provisioning operator.
-- No passwords are stored here. Set them through a protected channel before connecting.
BEGIN;
CREATE ROLE mspi_common_migrator LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS;
CREATE ROLE mspi_common_runtime LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS;

SELECT format('REVOKE ALL ON DATABASE %I FROM PUBLIC', current_database()) \gexec
SELECT format('GRANT CONNECT ON DATABASE %I TO mspi_common_migrator, mspi_common_runtime', current_database()) \gexec
REVOKE CREATE ON SCHEMA public FROM PUBLIC;

CREATE SCHEMA comun AUTHORIZATION mspi_common_migrator;
REVOKE ALL ON SCHEMA comun FROM PUBLIC;
GRANT USAGE ON SCHEMA comun TO mspi_common_runtime;
SELECT format('ALTER ROLE mspi_common_runtime IN DATABASE %I SET search_path = comun', current_database()) \gexec
COMMIT;
