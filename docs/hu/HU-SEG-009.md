# HU-SEG-009. Presupuestos separados para el acceso público y las sesiones activas

En P21 una ráfaga agotó la cuota por dirección y bloqueó también a una sesión legítima que compartía esa IP. La nueva admisión separa los contadores antes de comprobar CSRF, consultar PostgreSQL o leer el cuerpo.

| Tráfico | Identificador | Límite inicial por ventana de 1 minuto |
| --- | --- | ---: |
| Anónimo | Dirección TCP vista por el backend | 1.200 |
| POST de inicio de sesión | Dirección TCP vista por el backend | 120 |
| Sesión autenticada reconocida por el servidor | UUID de cuenta, compartido por todas sus sesiones | 1.200 |

Las solicitudes de recuperación e inscripción usan el presupuesto anónimo incluso si traen una sesión. Un POST de inicio de sesión usa siempre el presupuesto de ingreso. Las comprobaciones vigentes de autenticación, revocación, CSRF, permisos y límites de intentos de contraseña siguen aplicándose. Las cookies inventadas y las cabeceras de IP no habilitan el presupuesto de cuenta.

Cada contador tiene su propio registro acotado por `mspi.load.tracked-addresses`. La concurrencia global se mantiene en 32 por defecto. Las solicitudes públicas pueden ocupar hasta 24 de esos cupos, dejando capacidad para sesiones reconocidas. El cupo de evidencias sigue compartido y puede saturarse. Una vez alcanzado un límite se devuelve 429 con `Retry-After` o 503 si faltan cupos de concurrencia.

El alcance es la admisión dentro del backend. La red, las conexiones HTTP y los recursos globales de Docker siguen siendo compartidos. Los límites de nuevas sesiones también se mantienen, por lo que una ráfaga puede impedir nuevos inicios sin invalidar sesiones ya activas. La comprobación P21 usa el mismo equipo y una IP común. La prueba física desde la LAN sigue pendiente.

Las propiedades `mspi.load.login-requests-per-address` y `mspi.load.requests-per-account` se definen en la configuración base. Para una instalación que sustituya por completo esa configuración, conservar ambas propiedades. El esquema de base y los códigos de error de la API se mantienen.
