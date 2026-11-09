# Límites de entrada y sobrecarga — HU-SEG-005

HU-SEG-009 amplía esta política con [presupuestos separados por tipo de tráfico](hu/HU-SEG-009.md). Los límites de dirección y concurrencia de esta ficha describen la entrega inicial.

La aplicación aplica un control común de admisión antes de CSRF, validación de sesión en PostgreSQL, lectura de cuerpo y controladores. Actúa en las dos cadenas de Spring Security: también cubre rutas ajenas a la API que podrían crear sesiones anónimas al comprobar CSRF. Los rechazos conservan las cabeceras de seguridad. Los permisos y CSRF existentes siguen vigentes.

## Límites activos por instancia

| Propiedad | Valor inicial | Efecto |
| --- | --- | --- |
| mspi.load.window | 1m | Ventana fija por dirección del cliente |
| mspi.load.requests-per-address | 1200 | Solicitudes admitidas por dirección y ventana; también cuentan intentos que luego agotan concurrencia |
| mspi.load.tracked-addresses | 1024 | Cota de memoria del registro de clientes; no se expulsan presupuestos activos para admitir nuevas direcciones |
| mspi.load.concurrent-requests | 32 | Solicitudes simultáneas dentro de las cadenas de seguridad |
| mspi.load.concurrent-evidence | 2 | Cupo compartido para rutas de evidencias y cualquier cuerpo multipart, incluso en rutas no autorizadas |
| mspi.load.max-sessions | 256 | Sesiones creadas a través del control; tanto anónimas como autenticadas |
| mspi.load.json-bytes | 65536 | Tamaño declarado de cuerpos no multipart y tamaño real de cuerpos leídos por el flujo, incluidos JSON |
| mspi.load.multipart-bytes | 10016384 | Rechazo temprano del tamaño declarado de multipart; el límite real de multipart lo aplica también spring.servlet.multipart.max-request-size |

Un exceso por dirección responde **429 DEMASIADAS_SOLICITUDES**. La falta de cupos o de espacio para nuevas direcciones responde **503 CAPACIDAD_AGOTADA**. Ambas respuestas incluyen Retry-After; este valor orienta cuándo reintentar y no garantiza disponibilidad al vencer. Un cuerpo demasiado grande responde **413 CARGA_DEMASIADO_GRANDE**. Los errores omiten el contenido del cuerpo y usan no-store y correlationId. HEAD conserva el estado y las cabeceras sin cuerpo.

Los contadores y semáforos se comparten entre todos los clientes de esta instancia. Los cupos de solicitudes se liberan en finally, incluidos errores, rechazos de cuerpos y fallos de negocio. La saturación de archivos no ocupa todos los cupos ordinarios. No hay colas de espera dentro del control ni creación de hilos por solicitud.

El cupo de sesión se reserva antes de crearla; se libera al invalidarla o cuando el contenedor la expira. El cambio de identificador de sesión al autenticar conserva el mismo cupo. La expiración depende también del barrido del contenedor. El control no expulsa sesiones existentes para aceptar otras nuevas. La consulta CSRF inicial y las comprobaciones CSRF de solicitudes inválidas pueden crear sesiones anónimas. Todas pasan por la misma cota antes de cualquier creación de sesión por Spring Security.

## Servidor HTTP

La configuración base fija 64 hilos de Tomcat, mínimo 4, 128 conexiones y cola de aceptación 32; cabeceras de hasta 8 KiB, espera inicial y keep-alive de 5 segundos y hasta 100 solicitudes por conexión. El descarte de cuerpos rechazados se limita a 64 KiB; el servidor puede cerrar la conexión en cargas grandes. Multipart permite hasta 4 partes y conserva los límites de 10 000 000 bytes por archivo y 10 016 384 por solicitud. Los campos de formulario tienen límite de 64 KiB.

El límite JSON se comprueba sobre el flujo leído, no solo sobre Content-Length. La lectura usa buffers del consumidor sin acumular el cuerpo completo en el filtro. La lectura de parámetros de formulario y multipart puede realizarse dentro del contenedor, sin utilizar el flujo del wrapper: esos formatos conservan sus propios límites de 64 KiB para campos y 10 016 384 bytes para multipart. Subir mspi.load.multipart-bytes no aumenta ese máximo. Los valores de ambos límites deben mantenerse coherentes si una entrega posterior los cambia.

Se configura forward-headers-strategy=none: las cuotas usan la dirección remota del contenedor y no X-Forwarded-For enviado por el cliente. En una exposición por proxy se deberá configurar la confianza en intermediarios y probar la identidad del cliente; esto pertenece a la siguiente entrega de HTTPS. El perfil actual no acredita esa exposición.

## Pruebas y operación

- ApiLoadGuardTests: cuotas por dirección, recuperación al vencer ventana, registro de direcciones lleno sin crecimiento ilimitado, conservación de capacidad ordinaria bajo saturación de archivos, liberación única de cupos y concurrencia simultánea.
- ApiLoadFilterTests: cabeceras de IP falsificadas, rechazo antes de invocar el resto de la aplicación, creación e invalidación de sesiones, lectura sin tamaño declarado y liberación tras excepciones.
- ApiLoadIntegrationTests: Tomcat y PostgreSQL reales desechables, sesión CSRF, límite de sesiones, autenticación con rotación de cookie sin gastar otro cupo, cuerpos grandes con Content-Length y transferencia chunked, permanencia de sesión válida y recuperación del cupo tras logout.
- Regresión completa: contratos, permisos, sesiones, archivos, invitaciones y recuperación.

Los límites iniciales son presupuestos conservadores de configuración, no capacidad medida del computador del usuario. Antes de la evaluación se debe realizar carga progresiva autorizada sobre la instalación aislada y medir CPU, memoria, disco, tiempos y tráfico legítimo. Ajustar límites solo con evidencia, sin desactivarlos. El equipo de desarrollo no tiene Docker disponible; la suite PostgreSQL se ejecuta en CI con Java 17.

## Alcance y trabajo pendiente

Esta entrega aborda parte de H-02/P21 y añade controles al servidor HTTP de H-06/P22. **No cierra H-02 ni H-03 por completo.** Un atacante puede consumir presupuestos compartidos y negar nuevas sesiones; los clientes detrás de una misma IP también comparten cuota. Se conserva el limitador de identidad previo, incluido su límite global y por cuenta, sin declarar resuelto el bloqueo inducido.

Las operaciones HTTP actuales son síncronas. Si se añaden controladores asíncronos, habrá que prolongar la vida de los cupos hasta la finalización real. Las sesiones y cuotas son locales al proceso, sin coordinación entre réplicas ni persistencia de sesiones entre reinicios.

Quedan pendientes los presupuestos de CPU/RAM del proceso, disco temporal, tiempos y cuotas SQL comunes y empresariales, inspección de archivos maliciosos, HTTPS y proxy, y una evaluación de capacidad en el equipo real. El tiempo inicial de conexión de Tomcat no equivale a un plazo absoluto para toda petición: solicitudes lentas o transferencias prolongadas requieren el control adicional de proxy y despliegue. Estos límites de aplicación no impiden la saturación del enlace ni protegen frente al administrador del equipo.

No hay migraciones nuevas de base de datos ni cambios de permisos o modelos de negocio. OpenAPI pasa a 1.13.0 e incluye respuesta Problem por defecto en las operaciones que no la tenían, para describir los rechazos transversales. Referencias: [OWASP, prevención de denegación de servicio](https://cheatsheetseries.owasp.org/cheatsheets/Denial_of_Service_Cheat_Sheet.html), [propiedades de Spring Boot](https://docs.spring.io/spring-boot/appendix/application-properties/index.html).
