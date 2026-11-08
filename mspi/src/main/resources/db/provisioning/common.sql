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
ALTER ROLE mspi_common_runtime CONNECTION LIMIT 12;
SELECT format('ALTER ROLE mspi_common_runtime IN DATABASE %I SET statement_timeout = ''5s''', current_database()) \gexec
SELECT format('ALTER ROLE mspi_common_runtime IN DATABASE %I SET lock_timeout = ''1s''', current_database()) \gexec
SELECT format('ALTER ROLE mspi_common_runtime IN DATABASE %I SET idle_in_transaction_session_timeout = ''5s''', current_database()) \gexec
SELECT format('ALTER ROLE mspi_common_runtime IN DATABASE %I SET transaction_timeout = ''15s''', current_database()) \gexec
SELECT format('ALTER ROLE mspi_common_runtime IN DATABASE %I SET work_mem = ''4MB''', current_database()) \gexec
SELECT format('ALTER ROLE mspi_common_runtime IN DATABASE %I SET temp_file_limit = ''64MB''', current_database()) \gexec
SELECT format('ALTER ROLE mspi_common_runtime IN DATABASE %I SET max_parallel_workers_per_gather = ''0''', current_database()) \gexec
COMMIT;
