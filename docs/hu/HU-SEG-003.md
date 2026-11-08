# HU-SEG-003 — Revocación consistente de sesiones

Estado: implementación y pruebas preparadas; pendiente de validación automatizada en PR.
Rama: `fix/HU-SEG-003-invalidacion-sesiones`.
Base: `Develop`, commit `6e878b87c98099ed20536a42d95110ae041ee1d1`.

Como responsable de seguridad, quiero que una cuenta desactivada no conserve acceso y que rehabilitarla exija autenticación nueva, sin interrumpir las sesiones de otros usuarios.

## Implementación

- `CurrentActiveSession` aplica una única regla de vigencia en el filtro y en los servicios: vencimiento, cuenta existente y habilitada, y versión vigente.
- Flyway común V13 incrementa la versión al abandonar HABILITADA y cubre cuentas ya inactivas durante la instalación. El trigger no usa SECURITY DEFINER ni añade privilegios al runtime.
- El filtro existente invalida el contexto y la sesión del servidor cuando recibe una solicitud con identidad revocada. Las consultas protegidas devuelven 401 NO_AUTENTICADO.
- Se actualizan el contrato de consulta de sesión y la guía de operación. No cambia el formato de respuesta ni la versión empresarial V7.

Trazabilidad: hallazgo H-08, SEC-005 y prueba P05 del diagnóstico de seguridad; ASVS 5.0.0 V7.4.2, terminación de sesiones al deshabilitar cuentas. Esto aporta evidencia al control, sin declarar conformidad global de ASVS.

## Criterios y verificación

| Criterio | Prueba automatizada |
| --- | --- |
| Desactivar una cuenta revoca varias sesiones | SessionIntegrationTests: parametrizada para DESHABILITADA y PENDIENTE_VERIFICACION |
| Sesión y rutas protegidas rechazan cookies revocadas sin exponer identidad ni secretos | assertRejectedSession, 401 NO_AUTENTICADO |
| Las otras cuentas conservan sesión y acceso | SessionIntegrationTests: cuenta independiente |
| Rehabilitar sin solicitudes intermedias no revive cookies | reactivationWithoutInterveningRequestsDoesNotReviveOldCookie |
| Una cuenta eliminada no conserva sesión | deletedAccountCannotKeepUsingItsSession |
| Es posible autenticarse nuevamente al rehabilitar | Pruebas de desactivación y rehabilitación |
| Actualización V12→V13 conserva cuentas activas y revoca versiones inactivas | SessionRevocationMigrationTests, PostgreSQL desechable separado |
| Las actualizaciones sin transición no revocan sesiones | SessionRevocationMigrationTests |
| Instalación y repetición de migraciones | SchemaMigrationTests, común V13 y empresarial V7 |
| Permisos por empresa y recuperación siguen funcionando | Suite completa de regresión del backend |

La prueba de eliminación usa únicamente datos ficticios sin dependencias. No se añade una función pública de borrado. La revocación se aplica en la próxima solicitud; no interrumpe operaciones en curso. [Despliegue y límites](../SESION.md#revocación-por-estado-de-cuenta--hu-seg-003).

## Evidencia

La validación completa se ejecutará en el PR con Java 17 y PostgreSQL 17.11 desechable. En el equipo local Docker no está disponible y Java predeterminado es 21; no se declara ejecutada localmente la suite Java.

Antecedente: HU-SEG-002 integrada mediante [PR #20](https://github.com/jose6668/Software_MSPI-back/pull/20), commit base de esta entrega.
