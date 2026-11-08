# Recursos de ejecución y SQL — HU-SEG-007

Se conserva el control de entrada de HU-SEG-005 y HTTPS de HU-SEG-006. Esta entrega actúa dentro de las conexiones de base y proporciona una ejecución Docker con presupuestos verificables. Los pools empresariales existentes siguen siendo los mismos: se les añade la política SQL después de RESET ALL, sin crear un segundo sistema de pools.

## Presupuesto SQL activo

| Parámetro | Valor inicial | Alcance |
| --- | --- | --- |
| statement_timeout | 5 s | Cada sentencia runtime; PostgreSQL cancela el trabajo que excede el plazo |
| lock_timeout | 1 s | Espera por un bloqueo, menor que el plazo de sentencia |
| idle_in_transaction_session_timeout | 5 s | Cierra sesiones que abandonan una transacción abierta |
| transaction_timeout | 15 s | Cierra una transacción que prolonga su duración mediante muchas sentencias cortas; requiere PostgreSQL 17 |
| work_mem | 4 MiB | Presupuesto de cada operación de ordenación/hash, no de toda la consulta |
| max_parallel_workers_per_gather | 0 | Evita multiplicar trabajadores por consulta runtime |
| temp_file_limit | 64 MiB | Tope de archivos temporales de cada proceso PostgreSQL; no cubre tablas temporales explícitas |
| Pool común | 8 conexiones, espera 2 s | Mismo pool Hikari configurado antes de arrancar; no se abre otro |
| Pools empresariales | Máximo 4 pools de hasta 2 conexiones | Mantienen su configuración explícita y expulsión de pools inactivos; espera de 250 ms a 5 s |

El máximo de conexiones de la aplicación por proceso es 8 comunes + 4 × 2 empresariales = 16; réplicas adicionales multiplican ese presupuesto. Los defaults de rol admiten hasta 12 conexiones comunes y 4 por identidad empresarial para conservar margen de operaciones técnicas. El perfil de evaluación limita además el servidor PostgreSQL a 32 conexiones, conservando su reserva para administración. Dimensionar siempre el total del servidor, no sumar solo pools activos de una empresa.

mspi.sql-budget permite reducir esos presupuestos dentro de cotas validadas; no admite cero ni plazos ilimitados. El plazo de bloqueo debe ser menor que el de sentencia y la transacción debe poder durar más que una sentencia. Las propiedades de tenancy ahora rechazan más de 4 pools, más de 2 conexiones por pool o esperas de adquisición mayores de 5 s. La configuración anterior del arnés de pruebas, 2 pools de 1–2 conexiones, sigue siendo válida.

La conexión común recibe la política al obtenerla de Hikari. La conexión empresarial la recibe después de su limpieza al prestar y devolver; RESET ALL no deja desactivados los límites. Una conexión que no puede recuperar su política se descarta. Se limita la conexión de red a 3 s, la lectura del socket a un margen superior al plazo de sentencia y la señal de cancelación a 2 s. No se aceptan opciones de URL que sustituyan esos límites. Las migraciones y el operador quedan fuera de los presupuestos runtime para no cancelar mantenimiento a mitad de ejecución.

Los rechazos conocidos de consultas, bloqueos, pools o archivos temporales devuelven HTTP 503 BASE_OCUPADA, no-store, correlationId y Retry-After. No incluyen SQL, credenciales ni identificadores empresariales. No se registra cada rechazo esperado como error 5xx para evitar amplificación de logs. Reintentar una escritura requiere volver a consultar su estado/revisión: una desconexión no prueba por sí sola que no se confirmó.

## Actualizar una base existente

Antes de desplegar esta revisión, el operador de aprovisionamiento debe ejecutar db/provisioning/runtime-budgets.sql en la base dedicada de MSPI y reiniciar los pools/aplicación. Es idempotente y solo ajusta los roles runtime existentes; no migra ni borra datos y no modifica roles de migración. El operador debe poder cambiar parámetros restringidos de roles. No conceder esa capacidad al runtime.

Los bootstrap de bases y empresas nuevas incorporan los mismos defaults. Se mantienen común V13 y empresarial V7: no se modifican migraciones ya aplicadas. Los defaults de ALTER ROLE se aplican en nuevas conexiones; las conexiones abiertas no se terminan automáticamente. El backend rechaza conexiones con temp_file_limit ausente, ilimitado o superior a 64 MiB: una instalación antigua debe aplicar primero el script operativo. Esto requiere coordinación con el compañero que utilice esa base.

El límite de temporales lo establece el operador porque PostgreSQL no permite al runtime cambiar libremente temp_file_limit. No se concede GRANT SET de ese parámetro al runtime. Las sentencias de configuración de sesión restantes no sustituyen autorización ni prevención de inyección; un acceso directo con privilegios administrativos sigue fuera del alcance de protección de la aplicación.

## Ejecución aislada con cotas del sistema

El archivo deploy/resources/compose.yaml crea una instalación de evaluación independiente. No adopta ni sobrescribe automáticamente la base que ya utiliza el equipo. Requiere Docker Linux con cgroup v2, un JAR construido con JDK 17, certificados de HU-SEG-006 y secretos externos. Las imágenes de Java 17 JRE y PostgreSQL 17.11 están fijadas por digest. El contexto de build solo admite el Dockerfile y el JAR; no envía carpetas de claves ni archivos .env al build.

| Servicio | CPU | Memoria/swap | Procesos/hilos | Temporales |
| --- | --- | --- | --- | --- |
| Backend | 2 CPU | 1 GiB, sin swap adicional | 128 | /tmp tmpfs de 128 MiB |
| PostgreSQL | 1 CPU | 512 MiB, sin swap adicional | 96 | /tmp 64 MiB; temporales SQL sujetos al límite por proceso |

El backend usa UID/GID 10001, raíz de solo lectura, sin capacidades y sin nueva elevación de privilegios. Su heap máximo es 512 MiB, metaspace 192 MiB y memoria directa 128 MiB; la cota del contenedor cubre también memoria nativa y resto del proceso. Solo publica 8443 en 127.0.0.1. PostgreSQL no publica puertos al host. Los logs de ambos servicios rotan con un máximo configurado de tres archivos de 10 MiB por servicio.

El perfil https-container extiende la receta TLS existente para escuchar dentro de la red Docker. Se niega a arrancar fuera de Docker o si faltan las cotas cgroup v2, usuario no root, raíz de solo lectura o tmpfs acotado. La escucha interna 0.0.0.0 no equivale a publicación en la LAN: la publicación del host queda en loopback y se comprueba en CI. No usar este perfil con un docker run improvisado sin esos controles.

Los secretos llegan como archivos de solo lectura: la aplicación recibe únicamente la clave TLS del servidor y su contraseña de PostgreSQL. No se monta la clave privada de la CA ni la contraseña del operador en el backend. Spring configtree resuelve esos archivos, sin incluir sus valores en los argumentos ni en la configuración pública del contenedor.

Preparación del operador:

1. Construir mspi/ con JDK 17 y generar material TLS privado fuera de Git con la receta de HU-SEG-006.
2. Preparar archivos privados separados para el password del operador y el runtime. Definir fuera de Git MSPI_TLS_KEYSTORE_FILE, MSPI_TLS_PASSWORD_FILE, MSPI_DB_ADMIN_PASSWORD_FILE y MSPI_DB_RUNTIME_PASSWORD_FILE con sus rutas absolutas; MSPI_HTTPS_PORT puede conservar 8443. La plantilla pública deja las rutas vacías.
3. Comprobar permisos: el usuario del contenedor debe leer solo los archivos montados. En Linux, asignar los secretos al UID de servicio o usar una preparación de permisos restringida; no abrir directorios al resto del equipo. En Windows se conserva la ACL privada del host, y debe verificarse la lectura con Docker Desktop. Esta receta se prueba en Linux CI, no se acredita todavía en el Docker Desktop local.
4. Ejecutar docker compose -f deploy/resources/compose.yaml -p mspi-evaluacion build backend y después up -d database con ese mismo proyecto. Aprovisionar la base nueva con los scripts existentes, configurar las credenciales del migrador/runtime por canal protegido y ejecutar las migraciones con el CLI técnico y JDK 17. Ver preparación de base y migraciones de las HUs originales. No usar el rol runtime para migrar.
5. Aplicar runtime-budgets.sql a instalaciones previas o para verificar/reaplicar la política, y arrancar backend con el mismo compose/proyecto. La ruta HTTPS es https://localhost:8443/api/v1. La red o Internet siguen entrando mediante la receta de exposición autorizada; no publicar PostgreSQL ni el puerto interno en todas las interfaces.

El script scripts/resources/test_container_budget.py es exclusivamente para CI: crea un proyecto aleatorio identificado como mspi-rsrc-test-, genera credenciales ficticias, aprovisiona una base desechable, prueba y elimina únicamente ese proyecto verificado. No reutilizarlo para levantar una base real con datos. El volumen de una instalación de evaluación con datos debe conservarse y respaldarse; la limpieza destructiva pertenece solo al arnés desechable.

## Pruebas y alcance real

SqlResourceIntegrationTests usa PostgreSQL real para cancelación por tiempo, bloqueo, transacción inactiva, transacción que repite operaciones, recuperación de pool lleno, restauración de límites al reutilizar conexiones y aislamiento entre empresas después de una cancelación. También fuerza un derrame de ordenación con un tope reducido en una base desechable y comprueba que el runtime no pueda desactivarlo.

La prueba de despliegue construye la imagen y arranca los servicios con HTTPS real y CA validada. Comprueba CPU/memoria/PID efectivos, ausencia de puertos de base, usuario, raíz protegida y secretos por archivos. Llena únicamente /tmp dentro del contenedor desechable, exige el rechazo al superar 128 MiB, retira su archivo de prueba y confirma recuperación del endpoint. No llena el disco del host ni destruye una aplicación del usuario.

El lanzador nativo de Windows recibe presupuestos de JVM equivalentes, pero ActiveProcessorCount orienta la JVM y no impone una cuota de CPU del sistema. Tampoco limita por sí solo todos los bytes nativos o del temporal de Windows. Para las cotas fuertes se debe usar el perfil aislado verificado. No se declara activada esta ejecución en el equipo local mientras Docker no esté operativo.

Las cifras son presupuestos iniciales, no capacidad medida del equipo. work_mem puede multiplicarse por varios nodos de una consulta y temp_file_limit es por proceso; los límites del contenedor son la última barrera. El volumen persistente de datos/evidencias requiere capacidad y cuota de almacenamiento del entorno: el tmpfs no limita ese volumen. Estos controles no garantizan disponibilidad ilimitada ni resuelven saturación del enlace, inspección antimalware o recuperación integral de copias.

Referencias: [PostgreSQL 17: plazos de sesión](https://www.postgresql.org/docs/17/runtime-config-client.html), [recursos PostgreSQL](https://www.postgresql.org/docs/17/runtime-config-resource.html), [HikariCP](https://github.com/brettwooldridge/HikariCP), [Compose](https://docs.docker.com/reference/compose-file/services/).
