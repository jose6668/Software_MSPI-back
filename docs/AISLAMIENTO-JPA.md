# Aislamiento JPA empresarial

HU-004 continúa las migraciones y privilegios de HU-003. Separa persistencia común y empresarial sin usar una conexión privilegiada ni un EntityManagerFactory por empresa.

## Ámbitos de persistencia

- Unidad `common`: datasource común de runtime, entidades en `..internal.persistence.common..`, esquema explícito `comun` y validación de mapeo Hibernate. Repositorios y transacciones comunes usan `commonEntityManagerFactory` / `commonTransactionManager`; conservan los alias predeterminados de Spring.
- Unidad `enterprise`: entidades en `..internal.persistence.tenant..`, un EntityManagerFactory compartido y proveedor multitenant de Hibernate. Cada conexión usa el runtime exclusivo de la empresa. No hay datasource común de respaldo para operaciones empresariales. La conexión común que Hibernate solicita para metadatos no permite ejecutar operaciones empresariales.
- `ModularityTests` exige clasificar cada entidad de producción en uno de estos paquetes. Los futuros módulos conservan sus entidades y repositorios internos; deben registrar sus repositorios en la unidad y transaction manager correspondientes.

Flyway sigue siendo el único responsable del DDL. La unidad empresarial no valida todos los esquemas al arrancar ni los migra: comprueba estado/versión del registro y propietario en cada conexión utilizada. La verificación integral de preparación y recuperación se amplía en HU-005. Las cachés Hibernate de segundo nivel y de consultas están deshabilitadas; el contexto JPA pertenece a una transacción. `open-in-view` permanece deshabilitado.

## Unidad de trabajo y autorización

`empresas.api.TenantUnitOfWork.execute(actorId, companyId, operación)` es el contrato interno para iniciar una operación empresarial:

1. Exige identidad y empresa explícitas; rechaza comenzar dentro de otra transacción.
2. Invoca `TenantAccessPolicy` antes de consultar secretos o reservar conexiones.
3. Lee de nuevo el registro común: exige `ACTIVA`, versión estructural `1` y esquema derivado del UUID.
4. Reserva el pool exclusivo, fija el contexto del hilo y abre una transacción empresarial.
5. Al obtener una conexión verifica `current_user` y la única fila propietaria del esquema.
6. Confirma o revierte; limpia y devuelve la conexión; elimina el contexto y libera la reserva incluso ante errores.

Una llamada anidada con el mismo actor y empresa participa en la misma transacción. Cambiar cualquiera de ellos produce rechazo y marca rollback, aunque el llamador capture el error. No se propaga contexto a otros hilos. Las siguientes unidades vuelven a comprobar política y registro; una llamada anidada participa en la autorización de su unidad ya iniciada.

**La política predeterminada deniega todo acceso.** La identidad de sesión y la membresía real se implementan en HU-007/008. Su adaptador deberá contrastar el actor con la identidad autenticada por el servidor y consultar permisos vigentes; el UUID recibido por este contrato interno no acredita identidad. Las concesiones ficticias de pruebas no se empaquetan ni se exponen mediante HTTP. No se han creado endpoints empresariales.

## Conexiones y secretos

Cada pool se identifica por UUID y usa exactamente `mspi_run_<uuid sin guiones>`, sobre `emp_<uuid sin guiones>`. No utiliza el migrador ni el runtime común. Antes de reutilizar y devolver una conexión se revierte cualquier transacción pendiente, se restablecen autocommit, modo de lectura, rol y configuración de sesión, y se fija el esquema esperado. Una conexión que no pueda limpiarse se descarta.

Los límites son obligatorios al habilitar la función: máximo de pools, conexiones por pool, espera de conexión y TTL inactivo. Al reservar se eliminan pools vencidos e inactivos; si falta capacidad se retira el inactivo menos recientemente usado. Un pool reservado por una unidad activa no se desaloja. Si todos están ocupados, se rechaza la nueva unidad. El cierre de la aplicación cierra los pools. El TTL se evalúa al reservar, sin tarea periódica.

La función permanece deshabilitada por defecto (`mspi.tenancy.enabled=false`). Para una prueba técnica autorizada deben proporcionarse externamente:

```yaml
mspi:
  tenancy:
    enabled: true
    max-pools: 2
    connections-per-pool: 1
    connection-timeout: 5s
    idle-ttl: 30s
```

Estos números son del arnés de pruebas: no establecen capacidad ni políticas de producción. El pool común se dimensiona aparte. En cada proceso, las conexiones empresariales quedan acotadas por `max-pools × connections-per-pool`; se debe considerar también el número de réplicas al dimensionar PostgreSQL.

El adaptador de entorno resuelve, para cada UUID sin guiones, las propiedades externas:

```text
mspi.tenancy.connections.<uuidhex>.jdbc-url
mspi.tenancy.connections.<uuidhex>.username
mspi.tenancy.connections.<uuidhex>.password
```

Con variables de entorno se usan `MSPI_TENANCY_CONNECTIONS_<UUIDHEX>_JDBC_URL`, `_USERNAME` y `_PASSWORD`. La URL PostgreSQL no debe incluir credenciales. Contraseñas vacías o un usuario distinto del runtime esperado se rechazan. Los secretos se cargan al crear cada pool; rotarlos requiere retirar el pool inactivo, esperar su renovación o reiniciar el proceso. No hay refresco inmediato. La selección de un gestor de secretos y el dimensionamiento productivo siguen pendientes; `TenantSecretSource` permite sustituir el adaptador interno.

## Verificación y límites

`TenantIsolationTests` usa PostgreSQL real, dos empresas alternadas y una tercera para capacidad; un registro con el mismo ID y valores distintos demuestra aislamiento JPA. Comprueba permisos SQL cruzados, FK propietaria, commits y rollback, contexto fijo, concurrencia, reutilización y limpieza de conexiones, TTL y capacidad, propietario ausente, secretos inválidos y rechazos antes de resolver credenciales.

La tabla y entidad `isolation_probe`, los actores, las concesiones y la activación simulada existen exclusivamente en `src/test`. No constituyen un modelo de negocio ni un alta productiva. La separación de credenciales de HU-003 sigue siendo la barrera PostgreSQL incluso ante SQL calificado. La sesión, los permisos funcionales, el alta recuperable, HTTP y sus escenarios integrales se completan en sus HU correspondientes.
