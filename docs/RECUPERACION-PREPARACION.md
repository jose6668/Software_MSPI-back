# Preparación empresarial recuperable

HU-005 implementa el flujo técnico inicial de DMP-07, TR-EMP-03/04, AIS-17/18 y AT-09. Opera sobre una empresa ya reservada en `comun.empresas`, identificada por su UUID. HU-010 integra la reserva y la activación con invitación inicial. [Ciclo de empresas](CICLO-EMPRESAS.md).

## Ejecutar en un entorno de prueba

1. Aprovisionar la base dedicada y ejecutar `MigrationCli common` con el migrador común. La migración común V2 crea `comun.intentos_preparacion` y conserva las reservas existentes.
2. Con la identidad técnica autorizada, ejecutar `MigrationCli prepare <empresa-uuid>` para una reserva `EN_PREPARACION` o `ERROR_APROVISIONAMIENTO`. Para una reserva nueva, suministrar primero la URL y las credenciales del operador. Si los roles ya tienen contraseñas, se pueden suministrar todas las credenciales desde el primer intento.
3. Si devuelve 3, el esquema y los roles están preparados. Asignar contraseñas a ambos roles por el canal protegido del operador (por ejemplo, el comando interactivo `\password` de psql). Suministrar las credenciales indicadas abajo y repetir con el mismo UUID.
4. Cuando devuelve 0, comprobar en el registro técnico que el intento terminó `VERIFICADO`. La empresa queda `EN_PREPARACION` con su versión estructural verificada.

Desde `mspi/`, con el JAR empaquetado y Java 17, ejecutar en PowerShell:

```powershell
& "$env:JAVA_HOME\bin\java.exe" '-Dloader.main=software.mspi.empresas.internal.migration.MigrationCli' -cp target/mspi-0.0.1-SNAPSHOT.jar org.springframework.boot.loader.launch.PropertiesLauncher prepare $empresaUuid
```

En Linux/macOS, pasar el UUID en la variable de terminal `empresa_uuid`:

```bash
java -Dloader.main=software.mspi.empresas.internal.migration.MigrationCli -cp target/mspi-0.0.1-SNAPSHOT.jar org.springframework.boot.loader.launch.PropertiesLauncher prepare "$empresa_uuid"
```

| Variable | Uso |
| --- | --- |
| `MSPI_MIGRATION_JDBC_URL` | URL PostgreSQL externa, sin credenciales incluidas. Los tres accesos apuntan a la misma base |
| `MSPI_PREPARATION_USER` y `MSPI_PREPARATION_PASSWORD` | Operador técnico con autorización sobre el registro, la bitácora y el aprovisionamiento de roles y esquemas |
| `MSPI_MIGRATION_USER` y `MSPI_MIGRATION_PASSWORD` | Identidad migradora propia del UUID, `mspi_mig_<uuidhex>` |
| `MSPI_PREPARATION_RUNTIME_PASSWORD` | Secreto del runtime propio del UUID. El nombre `mspi_run_<uuidhex>` se deriva internamente |

El operador, el migrador y el runtime se usan en conexiones separadas. El proceso ordinario mantiene su conexión común de lectura y los runtimes empresariales acotados de HU-004. El runtime común carece de permisos sobre la bitácora. Las identidades de prueba de Testcontainers son exclusivas de los fixtures.

## Estado y recuperación

El coordinador toma un bloqueo de sesión PostgreSQL por base para procesar una empresa a la vez. Los comandos técnicos `prepare`, `tenant` y el script `tenant.sql` comparten la misma clave. Conectarse directamente a la misma base o mediante un proxy que preserve la sesión. Las lecturas de las empresas compatibles continúan con sus propias conexiones. [Bloqueos de sesión en PostgreSQL 17](https://www.postgresql.org/docs/17/explicit-locking.html#ADVISORY-LOCKS).

Al iniciar un intento, el coordinador invalida la versión de la reserva, registra `APROVISIONAR` y pasa por `CREDENCIALES`, `MIGRAR` y `VERIFICAR`. El aprovisionamiento conserva el UUID y los recursos completos del ámbito. Crea los recursos faltantes únicamente cuando no existe ninguno de los tres. Si encuentra una colisión o una estructura parcial, la rechaza sin tomar posesión de esos recursos.

El migrador empresarial aplica Flyway y valida su historial. La verificación posterior compara la versión instalada con la soportada por el backend y usa una conexión del runtime para comprobar la fila propietaria y sus permisos. Solo un intento verificado publica la versión instalada. La publicación y el resultado del intento se guardan en una transacción común, con comprobación de la revisión de la reserva.

| Resultado | Acción del operador |
| --- | --- |
| 0, estructura verificada | Continuar con los requisitos de activación cuando esté disponible HU-010 |
| 3, espera de credenciales | Asignarlas mediante el canal protegido y repetir con el mismo UUID |
| 1, intento fallido | Consultar el paso registrado, corregir su causa en un entorno separado y repetir sobre la misma reserva |
| 1, otro proceso mantiene el bloqueo | Esperar que concluya y volver a ejecutar el comando |
| 2, argumentos incorrectos | Revisar el formato del comando y volver a ejecutarlo |

Ante un fallo, el intento registra `FALLIDO` y el paso alcanzado. La empresa queda `ERROR_APROVISIONAMIENTO` sin versión utilizable. Si el proceso se corta antes de registrar el resultado, el siguiente intento sobre el mismo UUID marca el anterior `INTERRUMPIDO`. Repetir verifica de nuevo las etapas completadas y Flyway conserva las migraciones ya aplicadas. La bitácora almacena identificadores, paso, objetivo, resultado e instantes.

Los fallos transaccionales de las migraciones empresariales se pueden reintentar después de corregir su causa. Un checksum alterado, permisos excesivos o recursos incompatibles requieren diagnóstico y corrección por el operador. Ensayar cambios y recuperación en un entorno separado antes de usarlos con datos operativos.

## Actualizar una empresa existente a la versión vigente

1. Suspender la empresa en plataforma y esperar a que terminen las operaciones iniciadas. El comando de actualización rechaza una empresa activa.
2. Usar las credenciales de operador, migrador y runtime descritas arriba. Cambiar los argumentos del lanzador a `upgrade <empresa-uuid>`. El comando mantiene el bloqueo técnico global y la suspensión. En caso de error, consultar el intento, corregir la causa y repetir con el mismo UUID.
3. Con la versión vigente publicada tras la verificación, consultar la revisión en plataforma y reactivar con el backend compatible. En HU-019 se exige V9 empresarial después de aplicar V13 común. Programar la transición de las demás empresas para conservar su disponibilidad.

El rol migrador recibe permisos `REFERENCES` sobre las claves comunes de membresías y versiones. Para inicializar evaluaciones puede ejecutar una función común que verifica la identidad técnica y las referencias locales. El runtime empresarial conserva acceso a su propio esquema. [Borradores de evaluación](hu/HU-017.md).

El comando legado `tenant <uuid>` ejecuta Flyway para un esquema ya aprovisionado. Para el alta técnica recuperable se usa `prepare`, que registra el intento y verifica la versión. Para usar manualmente `tenant.sql`, conservar `tenant-bootstrap.sql` a su lado.
