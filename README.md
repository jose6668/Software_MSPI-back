# Software_MSPI-back
HTTPS local, LAN y origen de túnel: [HU-SEG-006](docs/hu/HU-SEG-006.md) y [puesta en marcha](docs/HTTPS-Y-EXPOSICION.md).
Protección de entrada ante sobrecarga: [HU-SEG-005](docs/hu/HU-SEG-005.md) y [límites y operación](docs/LIMITES-SOBRECARGA.md).
Política de contraseñas y bloqueo local de valores expuestos: [HU-SEG-004](docs/hu/HU-SEG-004.md) y [operación](docs/POLITICA-CONTRASENAS.md).
Revocación de sesiones por estado de cuenta: [HU-SEG-003](docs/hu/HU-SEG-003.md) y [operación](docs/SESION.md).

Prevención de secretos, configuración privada y comprobación automática de PR: [guía](docs/SECRETOS-Y-CONFIGURACION.md) y [HU-SEG-002](docs/hu/HU-SEG-002.md).

Plan de implementación por historias de usuario, criterios de aceptación y rúbrica: [Plan HU y rúbrica](docs/PLAN-HU-Y-RUBRICA.md).

Cada HU se desarrolla en una rama propia y se entrega mediante un PR hacia `Develop`, con pruebas y evidencias según su alcance.

La API empieza en `GET /api/v1` con la versión del contrato. Las convenciones de entrada, errores y correlación están en el [contrato REST](docs/CONTRATO-REST.md) y su [especificación OpenAPI](mspi/src/main/resources/openapi/v1.yaml).

El inicio y cierre de sesión usan una cookie del servidor y protección CSRF. El flujo HTTP, la preparación técnica de cuentas y los parámetros iniciales están en [Identidad y sesión](docs/SESION.md).

La sesión permite listar empresas propias y consultar el contexto mínimo de una empresa autorizada. [Membresías y permisos](docs/PERMISOS-EMPRESA.md).

HU-009 incorpora invitaciones de un solo uso por correo SMTP. La función requiere habilitación, una vigencia explícita y la configuración del relay y de la página de aceptación. [Flujo de invitaciones](docs/INVITACIONES.md).

HU-010 incorpora la reserva y activación con invitación inicial, la suspensión y la reactivación. [Ciclo de empresas y atribución del primer AP](docs/CICLO-EMPRESAS.md).

## Construcción y pruebas

Requisitos: JDK 17 completo, Docker con contenedores Linux y acceso a Maven Central y Docker Hub para la primera descarga. No hace falta instalar Maven o PostgreSQL: el wrapper fija Maven 3.10.0 y Testcontainers inicia PostgreSQL `17.11-alpine` temporalmente.

La raíz Maven es `mspi/`. Desde esa carpeta:

```powershell
# Windows: usar la ubicación real del JDK 17 instalado.
$env:JAVA_HOME = 'C:\ruta\al\jdk-17'
java -version
.\mvnw.cmd --version
docker info
.\mvnw.cmd --batch-mode --no-transfer-progress clean verify
```

En Windows, `java -version` consulta el PATH; la salida de `mvnw.cmd --version` confirma el JDK utilizado realmente por Maven a través de `JAVA_HOME`.

```bash
# Linux/macOS: JAVA_HOME debe apuntar al JDK 17.
cd mspi
bash ./mvnw --version
docker info
bash ./mvnw --batch-mode --no-transfer-progress clean verify
```

Maven Enforcer rechaza JDK distintos de 17. Las versiones de las dependencias de prueba se heredan de Spring Boot; no se necesita fijar otro BOM de Testcontainers.

`MspiApplicationTests.contextLoads` arranca con JPA y una conexión común de runtime sobre PostgreSQL 17. El setup técnico de pruebas prepara la base mediante Flyway y credenciales separadas; `JdbcConnectionDetails` entrega a la aplicación únicamente la conexión ordinaria. Spring gestiona el contenedor y lo destruye al cerrar el contexto. No usa una base instalada, secretos reales o puerto fijo. Sin Docker la prueba falla: no se omite automáticamente.

La aplicación no ejecuta Flyway automáticamente ni crea o actualiza tablas mediante Hibernate. HU-003 incorpora migraciones comunes/empresariales ejecutadas por un comando técnico separado. HU-004 comprueba el aislamiento JPA empresarial mediante un arnés interno; sesión y permisos reales se integran en HU-007/008. PostgreSQL 17.11 es la elección de pruebas y no fija producción.

Los reportes quedan en `mspi/target/surefire-reports/` y el JAR en `mspi/target/`. El workflow [Backend CI](.github/workflows/backend-ci.yml) ejecuta `clean verify` con Java 17 en cada PR hacia `Develop` y en los pushes a esa rama; conserva reportes de pruebas y no realiza despliegues.

## Problemas de entorno

- **Java incorrecto:** comprobar `JAVA_HOME` y `mvnw.cmd --version`. No basta con cambiar el nivel de lenguaje del IDE.
- **Docker inaccesible:** iniciar Docker Desktop con motor Linux y verificar `docker info`. Comprobar el acceso del usuario al motor; no deshabilitar Testcontainers para obtener una construcción verde.
- **No resuelve Maven Central/Docker Hub:** revisar DNS, red y proxy del entorno. La primera ejecución requiere descargar herramientas, dependencias e imágenes.
- **Caché incompleta de plugins:** ejecutar de nuevo con conectividad: `mvnw.cmd -U --batch-mode --no-transfer-progress clean verify`. Si persiste, usar un repositorio Maven vacío para comprobar la resolución (`"-Dmaven.repo.local=C:\ruta\temporal\mspi-m2"`), conservando la caché global y los logs. No cambiar versiones de Surefire ni omitir pruebas para ocultar el error.
- **Caché del wrapper sin permisos:** configurar `MAVEN_USER_HOME` en una ubicación escribible para esa terminal; es distinto del repositorio de dependencias controlado por `maven.repo.local`.

Ficha y evidencia de la primera entrega: [HU-001](docs/hu/HU-001.md).

## Arquitectura modular

El backend declara doce módulos cerrados con paquetes `api` e `internal`; `gestion` coordina a través de las interfaces públicas. Esta estructura no implica que los dominios de negocio ya estén implementados.

`clean verify` ejecuta además `ModularityTests`: comprueba los doce límites y demuestra que se rechazan ciclos, accesos a internos ajenos y dependencias no declaradas. Sus fixtures están fuera del código de producción.

Reglas y procedimiento para incorporar contratos reales: [Arquitectura modular](docs/ARQUITECTURA-MODULAR.md). Alcance y evidencia: [HU-002](docs/hu/HU-002.md).

## Migraciones

El registro común y cada esquema empresarial tienen migraciones e historial Flyway separados. El bootstrap utiliza una identidad de aprovisionamiento, cada ámbito tiene su migrador y el acceso ordinario usa roles sin DDL. Los esquemas e identidades empresariales derivan de UUID internos.

Preparación, permisos, comando técnico y límites de esta entrega: [Migraciones y privilegios](docs/MIGRACIONES-Y-PRIVILEGIOS.md). Criterios y evidencia: [HU-003](docs/hu/HU-003.md). Migrar no activa empresas ni completa el flujo de alta.

HU-005 incorpora el comando técnico `prepare <uuid>` para registrar intentos, verificar la estructura y reintentar sobre la reserva existente. La versión se publica tras la verificación y la empresa permanece en preparación. [Procedimiento de recuperación](docs/RECUPERACION-PREPARACION.md) y [ficha HU-005](docs/hu/HU-005.md).

## Persistencia empresarial

La persistencia común y la empresarial usan unidades JPA distintas. Cada unidad de trabajo empresarial fija actor y empresa durante la transacción, con el runtime exclusivo de esa empresa y pools acotados. Se deshabilitan las cachés compartidas de entidades y consultas.

La unidad JPA empresarial está deshabilitada por defecto. Activarla exige límites explícitos, secretos externos y autorización por operación. Las consultas del selector usan el registro común. [Aislamiento JPA](docs/AISLAMIENTO-JPA.md) y [permisos por empresa](docs/PERMISOS-EMPRESA.md).
