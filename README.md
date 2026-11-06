# Software_MSPI-back

Plan de implementación por historias de usuario, criterios de aceptación y rúbrica: [Plan HU y rúbrica](docs/PLAN-HU-Y-RUBRICA.md).

Cada HU se desarrolla en una rama propia y se entrega mediante un PR hacia `Develop`, con pruebas y evidencias según su alcance.

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

`MspiApplicationTests.contextLoads` arranca el contexto con JPA y Flyway habilitados, comprueba que el motor es PostgreSQL 17 y consulta la base mediante JPA. Spring gestiona el contenedor con `@ServiceConnection`; la base y la contraseña son exclusivas de esa ejecución y el contenedor se destruye al cerrar el contexto. No usa una base instalada ni un puerto fijo. Sin Docker la prueba falla: no se omite automáticamente.

El perfil `test` no permite crear o actualizar tablas mediante Hibernate. Las migraciones empresariales y las pruebas de aislamiento se incorporan en HU-003/HU-004; esta prueba de arranque no las sustituye. PostgreSQL 17.11 es la elección del entorno de pruebas de HU-001 y no fija la versión de producción.

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
