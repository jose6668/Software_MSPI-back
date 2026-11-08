# Política de contraseñas — HU-SEG-004

La creación por invitación, la confirmación de recuperación y la herramienta técnica `LocalPasswordHash` aplican la misma política base: mínimo de **15 puntos de código Unicode**, máximo de **1024 unidades UTF-16** (límite existente del backend), y rechazo de valores de la lista local de contraseñas expuestas. Las frases con espacios están permitidas; no se exige combinar mayúsculas, números y símbolos. No se recortan ni cambian las contraseñas antes de almacenarlas o verificarlas.

`mspi.invitations.min-password-length` y `mspi.recovery.min-password-length` deben estar entre 15 y 1024. La aplicación rechaza configuraciones inferiores; cada flujo puede exigir más de 15. La herramienta técnica usa el mínimo base de 15. La política se valida antes del cálculo costoso de hash. PBKDF2, sal aleatoria y los límites de concurrencia existentes se conservan.

Una contraseña rechazada devuelve HTTP 400, código `VALIDACION`, campo `contrasena`, sin incluir el valor propuesto. El rechazo no consume el comprobante de recuperación ni la invitación y permite elegir otra contraseña. Las verificaciones de comprobantes y CSRF continúan aplicándose.

El inicio de sesión conserva compatibilidad con credenciales anteriores; no ejecuta una lista de bloqueo nueva ni impone cambios masivos de contraseñas. Para renovar una credencial antigua se utiliza recuperación o el procedimiento autorizado. Ningún cambio de contraseña invalida las sesiones de otros usuarios. La recuperación mantiene su incremento de versión y revocación de sesiones anteriores.

## Lista local, procedencia y mantenimiento

La comprobación compara el valor completo, convertido a minúsculas con Locale.ROOT únicamente para consultar la lista. La contraseña original se mantiene para PBKDF2. No se envía la contraseña, su hash ni un prefijo de hash por la red. La comprobación funciona desconectada y no añade servicios ni credenciales.

Fuente: [SecLists, 100k-most-used-passwords-NCSC.txt](https://github.com/danielmiessler/SecLists/blob/1a7bb9127eca9e6ff2fc0301c597fe6e16a0cb56/Passwords/Common-Credentials/100k-most-used-passwords-NCSC.txt), revisión fijada `1a7bb9127eca9e6ff2fc0301c597fe6e16a0cb56`. El fichero fuente contiene valores reportados como expuestos. Se conservan solo aquellos que podrían superar el límite de longitud: 329 valores únicos en minúsculas de al menos 15 caracteres. Los valores más cortos ya son rechazados por longitud.

El repositorio incluye únicamente sus huellas SHA-256 en `security/blocked-passwords.sha256`, el manifiesto de procedencia y la licencia MIT de SecLists. Estas huellas pertenecen a un diccionario público, no son credenciales del sistema y no equivalen al almacenamiento de credenciales reales. SHA-256 solo sirve para consultar el diccionario; las cuentas siguen usando PBKDF2 con sal.

SHA-256 del fichero fuente: `c2e5696882c603b76bb67a47ee970897e5a76fc4c3f5547abe3d0ca340c576e0`.
SHA-256 del recurso derivado con LF: `ddcdfd3086502e9d0266b8b02ef9e33fec87ea56580a863496e39127d159047d`.

La ausencia de una contraseña en esta lista no demuestra que sea segura o que nunca haya sido comprometida. Es una lista acotada, sin actualización automática; debe revisarse periódicamente y antes de la evaluación. Para actualizarla: fijar una nueva revisión del proveedor, verificar origen y licencia, convertir valores completos a minúsculas, filtrar por longitud, generar las huellas SHA-256 UTF-8 únicas ordenadas con LF, actualizar manifiesto, tamaño esperado en PasswordPolicy y evidencia de pruebas mediante otro PR. Un recurso ausente, vacío o malformado impide usar la política en lugar de permitir contraseñas sin comprobación.

Referencia de diseño: [NIST SP 800-63B-4, contraseñas](https://pages.nist.gov/800-63-4/sp800-63b.html). Esta entrega no declara cumplimiento integral de NIST ni de ASVS. La normalización Unicode NFC, comprobación contextual de datos del usuario y un catálogo de filtraciones más amplio quedan como mejoras evaluables; el segundo factor para AP y AE sigue pendiente.

## Compatibilidad y despliegue

No requiere migraciones adicionales de base de datos; se mantienen común V13 y empresarial V7. El contrato OpenAPI pasa a 1.12.0 y describe el mínimo en invitación y recuperación; el login conserva su contrato. Las configuraciones con mínimos menores de 15 deben corregirse antes de arrancar. El frontend debe presentar el mensaje de validación sin registrar el cuerpo de la contraseña.
