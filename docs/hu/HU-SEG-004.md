# HU-SEG-004 — Política uniforme y bloqueo de contraseñas expuestas

Rama: `feat/HU-SEG-004-politica-contrasenas`, base `Develop` en `e1fac4da78ab861fa306a077340205be5455ed64`.

Como responsable de seguridad quiero impedir nuevas contraseñas demasiado cortas o presentes en una lista de valores expuestos, en todos los caminos de creación y recuperación.

Implementación: política compartida en identidad, mínimo de 15 puntos de código Unicode, configuración que no permite reducirlo, comprobación local mediante 329 huellas derivadas de una fuente pública fijada, y aplicación en invitaciones, recuperación y herramienta técnica. La creación por invitación también se protege en el codificador para impedir una omisión desde un llamador interno. Los errores no contienen la contraseña y no consumen los comprobantes. Se conservan credenciales existentes, hash PBKDF2, control de concurrencia, CSRF y aislamiento empresarial.

Trazabilidad: aborda la parte de longitud y contraseñas expuestas de H-04. El segundo factor queda en otra historia; H-04 no se declara cerrado por completo. [Política, fuente, límites y despliegue](../POLITICA-CONTRASENAS.md).

## Criterios y pruebas

- PasswordPolicyTests: rechazo de valores cortos, vacíos, expuestos y variantes de mayúsculas; frases con espacios; límite máximo; conteo Unicode; imposibilidad de configurar mínimos débiles; codificador de invitación protegido.
- InvitationPasswordSettingsTests: rechazo de mínimos inferiores a 15 y mayores que el máximo.
- InvitationTests: rechazo HTTP de contraseñas expuestas sin consumir invitación; posterior alta y login correctos.
- AccountRecoveryTests: rechazo HTTP sin cambiar versión ni consumir el comprobante; contraseña anterior sigue funcionando, y recuperación posterior correcta.
- Regresión completa: sesiones, recuperación, permisos y aislamiento por empresa.
- Higiene de secretos: archivos/plantillas y pruebas negativas con Gitleaks, además del workflow del PR.

La suite completa se valida en GitHub Actions con Java 17 y PostgreSQL desechable. Los resultados y enlaces de evidencia quedan en el PR de esta rama y en sus comprobaciones. No se atribuye ejecución local a los tests PostgreSQL: Docker no está disponible en el equipo de trabajo.

Antecedente: HU-SEG-003 integrada en [PR #21](https://github.com/jose6668/Software_MSPI-back/pull/21).
