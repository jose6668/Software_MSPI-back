# Identidad local y sesión de servidor

HU-007 implementa RF-02, CU-03, PER-04, DD-02/DD-05, TR-SES-01 a TR-SES-03 y la base de AT-14. La autenticación local, el algoritmo de hash y los parámetros de esta página son decisiones técnicas de la entrega. La política definitiva de producción queda pendiente.

## Flujo HTTP

1. El navegador consulta `GET /api/v1/identidad/csrf`. El servidor devuelve `headerName` y `token` y, al iniciar una sesión anónima, envía la cookie `MSPISESSION`.
2. El navegador envía `POST /api/v1/identidad/sesion` con la cookie, el token en `X-CSRF-TOKEN` y el cuerpo JSON con `correo` y `contrasena`. El servidor comprueba las credenciales, rota el identificador y descarta el token CSRF anterior.
3. `GET /api/v1/identidad/sesion` devuelve `usuarioId`, `nombreVisible` y `expiraEn` de la sesión vigente. Para una modificación después de iniciar sesión, el cliente vuelve a consultar el token CSRF.
4. `DELETE /api/v1/identidad/sesion` con el nuevo token y la cookie invalida la sesión en el servidor, expira la cookie y devuelve 204. La sesión anterior se rechaza en solicitudes posteriores.

Las respuestas de identidad y CSRF usan `Cache-Control: no-store`. La cookie se envía automáticamente en solicitudes del mismo origen. El cliente debe mantener el token CSRF fuera de las URL y los logs. La identidad de sesión conserva el UUID y el vencimiento. HU-008 usa esa identidad para volver a consultar la cuenta y sus permisos empresariales. El login HTTP Basic y el formulario de arranque de Spring se sustituyen por estas rutas. [Permisos por empresa](PERMISOS-EMPRESA.md), [contrato OpenAPI](../mspi/src/main/resources/openapi/v1.yaml) y [persistencia del contexto de seguridad](https://docs.spring.io/spring-security/reference/servlet/authentication/session-management.html).

| Condición | Respuesta |
| --- | --- |
| Sesión ausente, vencida o invalidada en consulta protegida | 401 `NO_AUTENTICADO` |
| Contraseña incorrecta, cuenta desconocida o deshabilitada | 401 `CREDENCIALES_INVALIDAS` |
| CSRF ausente o inválido en una modificación | 403 `CSRF_INVALIDO` |
| Límite de intentos o verificaciones simultáneas alcanzado | 429 `DEMASIADOS_INTENTOS` |

La verificación aplica un cálculo de hash también a cuentas desconocidas o deshabilitadas. Los errores y la vista de sesión omiten credenciales e identificadores de cookie.

## Aprovisionamiento técnico de cuentas

Aplicar primero Flyway común V3. La migración crea `comun.usuarios` y reserva la escritura al operador autorizado. La conexión común de runtime puede consultar únicamente las columnas requeridas por autenticación. Para habilitar una cuenta inicial, el operador autorizado registra el UUID, nombre, correo ya verificado, instante de verificación y hash. La clave de correo es el correo en minúsculas con `Locale.ROOT`, sin modificar puntos ni sufijos del proveedor. La base exige que esta clave sea única y que una cuenta habilitada tenga verificación y hash con formato válido.

Desde `mspi/`, el comando técnico permite generar el hash en una terminal interactiva con el JAR empaquetado:

```powershell
& "$env:JAVA_HOME\bin\java.exe" '-Dloader.main=software.mspi.identidad.internal.LocalPasswordHash' -cp target/mspi-0.0.1-SNAPSHOT.jar org.springframework.boot.loader.launch.PropertiesLauncher
```

El comando lee la contraseña en el prompt y entrega el hash al operador. Tratar el hash como secreto y cargarlo mediante parámetros protegidos en `hash_credencial`. La V3 admite PBKDF2-HMAC-SHA256 con sal aleatoria de 16 bytes y 600 000 iteraciones bajo el identificador `pbkdf2-sha256-v1`. La invitación y la recuperación de cuentas corresponden a HU-009 y HU-012. [Almacenamiento de contraseñas en OWASP](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html).

## Configuración y límites de operación

| Propiedad | Valor inicial | Efecto |
| --- | --- | --- |
| `server.servlet.session.timeout` | 20 min | Vencimiento por inactividad administrado por el contenedor |
| `mspi.identity.session-lifetime` | 8 h | Vencimiento absoluto comprobado en cada solicitud API |
| `mspi.identity.attempt-window` | 1 min | Ventana de intentos de la instancia |
| `mspi.identity.attempts-per-account` | 5 | Máximo por clave de cuenta normalizada |
| `mspi.identity.attempts-per-address` | 30 | Máximo por dirección del cliente informada por el contenedor |
| `mspi.identity.total-attempts` | 120 | Máximo por instancia y cota de memoria de los contadores |
| `mspi.identity.concurrent-checks` | 4 | Verificaciones costosas simultáneas |

Cada intento admitido consume cuota, con independencia de su resultado. La ventana fija empieza con el primer intento y se renueva al vencer. Las cuentas desconocidas están sujetas a los mismos límites. Detrás de un proxy, configurar en el contenedor únicamente intermediarios de confianza.

La cookie es `HttpOnly`, `Secure` y `SameSite=Lax` en la configuración inicial. El contenedor solo acepta seguimiento de sesión mediante cookie. Servir la aplicación por HTTPS fuera del desarrollo local. El desarrollo aislado por HTTP puede configurar `server.servlet.session.cookie.secure=false`. Las sesiones y los límites se mantienen por instancia y se reinician con el proceso. La recuperación incrementa la versión de credencial en PostgreSQL y la API comprueba esa versión en cada solicitud autenticada. La coordinación de los límites entre réplicas queda pendiente. [Recuperación de acceso](hu/HU-012.md).

## Revocación por estado de cuenta — HU-SEG-003

Cada solicitud API autenticada comprueba vencimiento, existencia de la cuenta, estado `HABILITADA` y versión de credencial. Una cuenta deshabilitada, pendiente de verificación o eliminada provoca la invalidación de la sesión al recibir la siguiente solicitud. Las consultas protegidas responden 401 `NO_AUTENTICADO`; el cliente debe descartar su vista autenticada. Las comprobaciones CSRF de las modificaciones siguen vigentes y pueden responder 403.

Aplicar Flyway común **V13 antes de desplegar este cambio**. Un trigger incrementa la versión al salir del estado `HABILITADA`, con independencia del operador autorizado que cambie el estado. Rehabilitar la cuenta no recupera cookies anteriores, aunque no hayan llegado solicitudes durante la desactivación. La instalación incrementa también la versión de las cuentas que ya estaban inactivas. Esto invalida igualmente sus comprobantes anteriores de recuperación, que comparten la versión; habrá que solicitar uno nuevo.

El cambio afecta a todas las sesiones de esa cuenta y conserva las de otros usuarios. La revocación de una membresía empresarial mantiene su comprobación de permisos por empresa y su respuesta 403. No se añade un endpoint de administración ni de eliminación de cuentas, ni cambia el esquema empresarial V7. La eliminación física se prueba con una cuenta ficticia sin referencias; las reglas de integridad de datos existentes siguen aplicándose.

El rechazo se verifica al comenzar cada solicitud; no cancela operaciones que ya estaban en ejecución. El operador privilegiado de PostgreSQL y el administrador del equipo continúan siendo límites de confianza. [Implementación y pruebas](hu/HU-SEG-003.md).
