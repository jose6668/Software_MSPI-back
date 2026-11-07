# Invitaciones por correo

HU-009 implementa RF-03, CU-07, PER-09/11, DD-04, TR-INV-01 a TR-INV-04, TR-MEM-01 y AIS-08. El canal elegido para esta historia es correo automático por SMTP. La vigencia sigue por definir y se configura de forma explícita en el entorno.

## Flujo

1. Un AE inicia sesión y obtiene el token CSRF. Envía `POST /api/v1/empresas/{empresaId}/invitaciones` con correo y uno a cuatro roles distintos entre AE, GS, RA y AU. La empresa debe estar activa y su versión debe ser compatible. La respuesta 201 contiene solo el identificador de la invitación y el vencimiento.
2. El backend genera un comprobante aleatorio de 256 bits, guarda su huella SHA-256 y envía al correo destinatario el enlace HTTPS configurado. El token viaja en el fragmento `#token=...`, que el cliente debe retirar de la URL y enviar en el cuerpo JSON. La página de destino es configuración del despliegue y su interfaz se integra aparte.
3. Si el destinatario ya tiene cuenta habilitada y correo verificado, inicia sesión y envía `POST /api/v1/invitaciones/aceptar` con el token y CSRF. El correo se compara con la clave normalizada de la identidad de sesión. Una cuenta distinta recibe el mismo rechazo que una invitación no disponible.
4. Si el correo aún no tiene cuenta, el destinatario puede enviar `POST /api/v1/invitaciones/alta` con el token, nombre visible y contraseña, más la cookie y el token CSRF de la sesión anónima. La posesión del enlace verifica el correo fijado al emitir. La cuenta y su membresía se crean juntas. Luego el destinatario inicia sesión con sus credenciales.
5. Un AE puede revocar una invitación pendiente de su empresa con `POST /api/v1/empresas/{empresaId}/invitaciones/{invitacionId}/revocar` y CSRF.

La respuesta de aceptación indica empresa, membresía, usuario y roles asignados. El token, los roles y la empresa efectivos provienen de la invitación almacenada. Un cuerpo de aceptación con campos adicionales se rechaza. El alta exige un comprobante vigente y nunca actualiza el correo o la contraseña de una cuenta existente. [Contrato OpenAPI](../mspi/src/main/resources/openapi/v1.yaml).

## Integridad y fallos

Flyway común V5 incorpora invitaciones y sus roles. El runtime común solo puede ejecutar las funciones específicas. Las tablas y huellas quedan fuera de sus lecturas directas. La sesión del servidor acredita al actor y la base revalida AE, empresa y destinatario en las operaciones que correspondan. [Seguridad de funciones PostgreSQL](https://www.postgresql.org/docs/17/perm-functions.html).

La aceptación bloquea la invitación, comprueba el reloj de PostgreSQL y crea la membresía con los roles almacenados. El consumo y la incorporación se confirman en la misma transacción. Una invitación usada, revocada, vencida, para otro destinatario o una empresa incompatible recibe 409 con `INVITACION_NO_DISPONIBLE`. La suspensión conserva el vencimiento original. Una membresía existente, incluida una revocada, conserva sus roles y estado.

La emisión conserva la transacción abierta durante la entrega al relay SMTP. Si el relay falla, devuelve 503 con `ENVIO_NO_DISPONIBLE` y revierte la invitación. La aplicación puede volver a emitir con un comprobante nuevo. SMTP y PostgreSQL son sistemas separados. Si el correo sale y luego falla la confirmación de la base, el enlace queda inutilizable y se requiere emitir de nuevo. Los errores conservan correlación sin publicar correo, comprobante ni diagnóstico SMTP.

## Configurar el entorno

La función se activa con `mspi.invitations.enabled=true`. Antes de activarla, aplicar Flyway común V5 y configurar:

| Propiedad | Valor requerido |
| --- | --- |
| `mspi.invitations.validity` | Duración acordada para el entorno. Se exige un valor positivo y carece de valor predeterminado |
| `mspi.invitations.acceptance-url` | URL HTTPS de la interfaz de aceptación, sin usuario, consulta ni fragmento |
| `mspi.invitations.from` | Remitente autorizado por el relay |
| `mspi.invitations.min-password-length` | Longitud mínima acordada para el alta local |
| `spring.mail.host` y `spring.mail.port` | Host y puerto SMTP compatibles con STARTTLS |
| `spring.mail.username`, `spring.mail.password` y `spring.mail.properties[mail.smtp.auth]` | Valores externos cuando el relay exige autenticación |

Se configuran STARTTLS obligatorio, verificación del servidor y tiempos de espera para conexión, lectura y escritura. El proveedor y sus secretos quedan a cargo del entorno. La salud general queda separada del relay SMTP. [Configuración de correo en Spring Boot](https://docs.spring.io/spring-boot/reference/io/email.html).

Las pruebas usan 72 horas y una longitud mínima de 15 como datos de prueba. Las empresas y cuentas son ficticias. El buzón de prueba registra el mensaje sin contactar un relay externo. HU-010 integra la primera invitación por AP con la activación de la empresa. Los listados administrativos corresponden a HU-011. [Ciclo de empresas](CICLO-EMPRESAS.md).
