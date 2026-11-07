# Migraciones por ámbito y privilegios iniciales

HU-003 concreta el mínimo técnico de DMP-01/02/04/07 y ARQ-13, según los documentos centrales 07/08 y APR-08, revisión 964249c. Estas elecciones de implementación son revisables en el PR; no amplían el alcance funcional ni resuelven políticas pendientes.

Para preparar o recuperar una reserva con seguimiento de intentos y verificación de versión, usar el [procedimiento de HU-005](RECUPERACION-PREPARACION.md). La secuencia manual de esta página describe las operaciones básicas introducidas en HU-003.

## Modelo físico mínimo

| Ámbito | Estructura versionada | Finalidad |
| --- | --- | --- |
| comun | empresas | Reserva de UUID, nombre, zona horaria, esquema derivado, estado, versión y revisión |
| emp_<uuid_sin_guiones> | contexto_empresa | Una empresa propietaria, fijada por CHECK y referenciada al registro común |
| Cada ámbito | flyway_schema_history | Historial Flyway independiente; la versión 1 de un ámbito no equivale a la de otro |

Se utilizan UUID generados por servidor, timestamptz para instantes y bigint positivo para revisión. Los textos no incorporan máximos arbitrarios no aprobados. La validación empresarial de nombre, zona IANA, normalización y datos adicionales se completa con sus operaciones; este registro técnico no es todavía el alta funcional de RF-01.

La restricción de esquema exige la concatenación de emp_ y el UUID sin guiones. No se deriva del nombre visible ni se recibe un nombre de esquema libre. MigrationScope solo admite ámbito común o UUID; las identidades técnicas también derivan de ese UUID.

contexto_empresa tiene PK empresa_id, una marca única que solo admite verdadero, CHECK que fija el UUID de su esquema y FK a comun.empresas. Runtime solo recibe SELECT sobre esa fila. Las futuras tablas empresariales deben tener empresa_id no nulo y FK local a la fila propietaria. Una tabla exclusiva de tests demuestra este patrón sin anticipar tablas de negocio.

MOD-01 queda aplicado a este mínimo. MOD-03, sobre referencias compuestas a membresías de la misma empresa, se conserva como obligación para el modelo de membresías; no se declara implementado. No se crean usuarios, credenciales, roles empresariales o invitaciones antes de resolver sus contratos y políticas.

Migrar no activa empresas ni actualiza automáticamente version_estructura en el registro común. Verificación, progreso y activación recuperable corresponden a HU-005/HU-010. Las empresas de las pruebas permanecen en preparación.

## Identidades y permisos

| Identidad técnica | Privilegios de esta entrega | Límites |
| --- | --- | --- |
| Aprovisionamiento | Crea roles/esquemas y aplica grants mediante scripts explícitos | Operación de infraestructura separada; nunca credencial de solicitudes |
| mspi_common_migrator | Dueño de comun; ejecuta sus migraciones | Sin superusuario, CREATE DATABASE/ROLE, replicación, BYPASSRLS ni membresías |
| mspi_common_runtime | CONNECT, USAGE de comun y SELECT del registro | Sin DDL, cambios de estado, acceso empresarial o historial Flyway |
| mspi_mig_<uuid> | Dueño de su esquema; USAGE de comun y REFERENCES de empresas.id | Sin SELECT del directorio común ni acceso a otra empresa |
| mspi_run_<uuid> | CONNECT, USAGE de su esquema y SELECT de la fila propietaria | No cambia propietario, hace DDL, asume rol migrador o lee historial Flyway |

Los grants sobre futuras tablas operativas se incorporan con sus migraciones. No se concede acceso global a todas las tablas. El bootstrap revoca privilegios de base a PUBLIC y CREATE en public; concede CONNECT explícito, sin TEMPORARY para runtime. Runtime no es dueño de esquemas o tablas.

search_path se fija por rol y base para facilitar resolución. La barrera no depende de él: las pruebas utilizan nombres completamente calificados y PostgreSQL rechaza accesos cruzados.

El bootstrap exige una base dedicada y un operador autorizado para crear y asignar roles y esquemas y administrar grants. Testcontainers usa su administrador para preparar fixtures. Los roles son globales al clúster. Los nombres por entorno y los permisos exactos del operador se revisarán antes del despliegue. El bootstrap común exige roles nuevos. El empresarial de HU-005 reutiliza recursos completos verificados y rechaza colisiones o conjuntos parciales.

## Aprovisionamiento inicial y ejecución

Recursos en el JAR y en mspi/src/main/resources:

- db/provisioning/common.sql: bootstrap inicial mediante psql y credencial de aprovisionamiento.
- db/migration/common/V1__registro_de_empresas.sql: estructura común mediante su migrador.
- db/provisioning/tenant.sql: reserva técnica de roles/esquema mediante psql y variable UUID.
- db/migration/tenant/V1__contexto_propietario.sql: estructura empresarial mediante su migrador.

Los scripts psql de aprovisionamiento no están en las ubicaciones Flyway. Se ejecutan explícitamente; no son un segundo motor de evolución de tablas. Sus cambios de roles/permisos/esquemas se agrupan en transacciones.

Secuencia inicial para un entorno dedicado:

1. Ejecutar common.sql con psql, conectado a la base elegida con la identidad de aprovisionamiento.
2. Asignar contraseñas por canal protegido a los roles comunes. En una sesión psql autorizada puede usarse \password mspi_common_migrator y \password mspi_common_runtime; los archivos SQL no contienen contraseñas.
3. Ejecutar el comando técnico common con la credencial migradora común.
4. Reservar empresa desde el proceso técnico autorizado, usando UUID generado, nombre y zona horaria proporcionados, y esquema derivado. No existe un endpoint público para hacerlo en esta HU.
5. Ejecutar psql con -v empresa_id=<uuid> -f mspi/src/main/resources/db/provisioning/tenant.sql. El script valida UUID y reserva EN_PREPARACION antes de crear identificadores; cita literales e identificadores mediante psql/format.
6. Asignar contraseñas por canal protegido al migrador y runtime de esa empresa.
7. Ejecutar tenant <uuid> con su migrador. Terminar y verificar una empresa antes de iniciar la siguiente; no ejecutar migraciones empresariales en paralelo. No combinar ámbitos ni usar credencial universal. La coordinación automatizada se incorpora en HU-005.
8. Verificar resultado y mantener bloqueada cualquier empresa incompleta. La recuperación y orquestación idempotente del alta completa corresponden a HU-005; los scripts son bootstrap inicial, no un flujo de reintento de negocio.

Variables del comando técnico:

| Variable | Valor |
| --- | --- |
| MSPI_MIGRATION_JDBC_URL | URL PostgreSQL sin usuario o contraseña incorporados |
| MSPI_MIGRATION_USER | Migrador del ámbito seleccionado |
| MSPI_MIGRATION_PASSWORD | Secreto suministrado externamente |

Desde mspi/, después de clean verify, PowerShell:

    & "$env:JAVA_HOME\bin\java.exe" '-Dloader.main=software.mspi.empresas.internal.migration.MigrationCli' -cp target/mspi-0.0.1-SNAPSHOT.jar org.springframework.boot.loader.launch.PropertiesLauncher common

Linux/macOS:

    java -Dloader.main=software.mspi.empresas.internal.migration.MigrationCli -cp target/mspi-0.0.1-SNAPSHOT.jar org.springframework.boot.loader.launch.PropertiesLauncher tenant <empresa-uuid>

La invocación devuelve salida no cero si falla. No imprime el secreto ni ofrece clean/repair. La factoría verifica identidad esperada y configura createSchemas(false), cleanDisabled(true) y validación antes de migrar. PostgreSQL refuerza la separación incluso si se evita esa comprobación Java.

## Arranque ordinario y pruebas

La aplicación mantiene Flyway automático, inicialización SQL básica y DDL Hibernate deshabilitados. No hay un bean migrador automático ni una credencial universal de migración en su arranque.

El acceso común ordinario se configura externamente mediante SPRING_DATASOURCE_URL, SPRING_DATASOURCE_USERNAME y SPRING_DATASOURCE_PASSWORD, con el rol común de ejecución. Selección JPA de conexiones empresariales y límites siguen en HU-004.

En tests, Spring sigue gestionando PostgreSQL. TestDatabase prepara scripts y migraciones con identidades técnicas; JdbcConnectionDetails entrega a JPA únicamente la conexión común runtime. Se reemplaza la conexión administrativa que proporcionaba automáticamente @ServiceConnection en HU-001. No se deshabilita JPA ni se sustituye PostgreSQL por H2.

SchemaMigrationTests verifica instalación, repetición, historiales separados, propietario/FK local, privilegios y accesos cruzados, falta de bootstrap, checksum alterado, rollback de DDL y comando técnico sin revelar secretos. MspiApplicationTests comprueba JPA con runtime y ausencia de Flyway automático. Las seis pruebas modulares permanecen activas.

La validación de entidades JPA por ámbito se completará con sus mapeos. Las pruebas SQL no demuestran aún contexto JPA empresarial, permisos HTTP, cachés, membresías, migración de todas las empresas o restauración completa.

Referencias: [esquemas PostgreSQL 17](https://www.postgresql.org/docs/17/ddl-schemas.html), [privilegios PostgreSQL 17](https://www.postgresql.org/docs/17/ddl-priv.html), [createSchemas Flyway](https://documentation.red-gate.com/flyway/reference/configuration/flyway-namespace/flyway-create-schemas-setting), [inicialización Spring Boot](https://docs.spring.io/spring-boot/how-to/data-initialization.html).
