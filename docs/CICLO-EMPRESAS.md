# Alta y disponibilidad de empresas

HU-010 implementa RF-01/05, CU-01/02, PER-01 a PER-03, DD-01, TR-EMP-01 a TR-EMP-06 y AT-09. Separa la administración de plataforma del contenido operativo. La ejecución técnica de HU-005 conserva sus identidades y sus secretos externos.

## Primer administrador de plataforma

Un operador autorizado prepara una cuenta habilitada con correo verificado mediante el procedimiento técnico de HU-007. Luego aplica Flyway común V6 y ejecuta `psql -v usuario_id=<uuid> -f mspi/src/main/resources/db/provisioning/platform-admin.sql` sobre la base dedicada con su identidad de aprovisionamiento. El script admite repetir el UUID inicial y rechaza otra cuenta si ya existe un AP. La atribución reside en `comun.atribuciones_plataforma` y no crea membresías. [Identidad y sesión](SESION.md).

## Alta en dos fases

1. El AP inicia sesión y envía `POST /api/v1/plataforma/empresas` con nombre, zona horaria IANA, contacto inicial y descripción opcional. La cabecera `Idempotency-Key` recibe un UUID y el navegador incluye CSRF. El servidor reserva un UUID y su esquema derivado en estado `EN_PREPARACION`.
2. La misma clave, actor y datos devuelven la reserva existente. Una clave reutilizada con datos diferentes produce 409. El nombre visible puede repetirse. La política de identificador institucional queda pendiente.
3. El operador ejecuta `MigrationCli prepare <empresa-uuid>` conforme a HU-005. Si el primer intento espera credenciales, prepara los roles por el canal protegido y repite con el mismo UUID. Las credenciales de migración y aprovisionamiento se mantienen en ese proceso técnico. [Preparación recuperable](RECUPERACION-PREPARACION.md).
4. El AP consulta `GET /api/v1/plataforma/empresas/{empresaId}`. El registro devuelve estado, revisión, versión y el último paso y resultado de preparación cuando existan. Para activar, envía `POST .../{empresaId}/activar` con `revisionEsperada` y CSRF. Se exige resultado `VERIFICADO`, versión compatible y comprobación actual con el runtime de la empresa.
5. El backend emite al contacto reservado una invitación que ofrece exclusivamente AE. El cambio a `ACTIVA`, la invitación y su evento se confirman juntos tras enviar al relay. El destinatario obtiene membresía al aceptar mediante HU-009. [Configuración y aceptación de invitaciones](INVITACIONES.md).

El runtime empresarial usado en la comprobación se resuelve mediante las mismas propiedades externas que HU-004. Debe conectar con su rol exclusivo, ver el propietario correcto y conservar permisos acotados. La comprobación de la aplicación usa el registro de versión verificado por Flyway y el esquema de runtime. La validación de checksum sigue en el proceso migrador. [Aislamiento JPA](AISLAMIENTO-JPA.md).

Si falla el envío inicial o la comprobación de runtime, la empresa queda bloqueada como `ERROR_APROVISIONAMIENTO` y registra una referencia correlacionada. Corregida la causa, el AP consulta la nueva revisión y repite la activación. Se reutiliza la preparación verificada si sigue siendo compatible. Un fallo previo de migración se recupera con el comando de HU-005. Si el correo salió pero la confirmación de base falla, el enlace carece de una invitación válida.

Antes de que se acepte la primera invitación, el AP puede reemitir al mismo contacto con `POST .../{empresaId}/invitacion-inicial/reemitir`, revisión vigente y CSRF. Una reemisión exitosa invalida cualquier enlace inicial todavía vigente. Los enlaces vencidos conservan su historial. Tras la primera aceptación, la gestión de invitaciones ordinarias corresponde al AE.

## Suspensión, reactivación y metadatos

- `GET /api/v1/plataforma/empresas` lista metadatos del servicio con paginación. La respuesta omite correo inicial, nombres de esquema y credenciales. AP por sí solo carece de acceso al contexto empresarial.
- `POST .../{empresaId}/suspender` exige `revisionEsperada`, `motivo` y CSRF. La siguiente solicitud empresarial queda bloqueada, incluso con una sesión anterior. Repetir con la revisión vigente conserva el motivo y el evento originales.
- `POST .../{empresaId}/reactivar` exige los mismos datos. Comprueba versión, propietario y permisos de runtime antes de pasar de suspendida a activa. Las membresías se mantienen y se revalidan en cada solicitud.

La V6 registra reserva, reintento, activación, fallo, reemisión, suspensión y reactivación con actor, estado, motivo cuando aplica y correlación. El runtime común ejecuta funciones específicas de plataforma y conserva permisos de lectura acotados. [OpenAPI](../mspi/src/main/resources/openapi/v1.yaml).
