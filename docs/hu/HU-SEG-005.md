# HU-SEG-005 — Admisión acotada ante sobrecarga

Rama: feat/HU-SEG-005-limites-sobrecarga, base Develop en 5e42cfe71305ae6e40c46c82bce4312b310a47eb.

Como responsable de seguridad quiero que la API rechace trabajo que excede su presupuesto, antes de crear más sesiones o ejecutar operaciones costosas, y que recupere capacidad al terminar las solicitudes.

Se implementan límites de solicitudes por dirección con memoria acotada, concurrencia global y de archivos, sesiones activas creadas por los flujos HTTP, y tamaño efectivo de cuerpos JSON. Las dos cadenas de seguridad aplican el mismo control. Se fijan límites explícitos del conector HTTP y se conserva autenticación, CSRF, permisos y política de contraseñas.

Criterios: rechazos 429/503 con Retry-After y 413 para tamaño; cupos liberados en excepciones; concurrencia real sin exceder el máximo; saturación de archivos compatible con solicitudes ordinarias; una sesión no consume dos cupos al cambiar de identificador; logout libera capacidad; cabeceras de IP enviadas por el usuario no eluden cuotas; cuerpo chunked grande rechazado sin alterar sesión válida.

Evidencia automatizada: ApiLoadGuardTests, ApiLoadFilterTests, ApiLoadIntegrationTests y la regresión completa del backend en CI. Los resultados y enlaces de ejecución se registran en el PR de esta rama. Las comprobaciones locales de higiene de secretos no sustituyen esa suite.

[Operación, límites y pendientes](../LIMITES-SOBRECARGA.md). H-02 y H-03 no se consideran cerrados globalmente. HTTPS, inspección de contenido malicioso y límites del proceso/SQL requieren entregas posteriores; no se incluye segundo factor. No cambia el esquema común V13 ni el empresarial V7.

## Evidencia de implementación

PR hacia Develop: [#23](https://github.com/jose6668/Software_MSPI-back/pull/23).
Commit de implementación: 5a71f32e9f3077049762b3cdabd60fa051342655.

[Backend CI](https://github.com/jose6668/Software_MSPI-back/actions/runs/42527468740): **143 pruebas, 0 fallos, 0 errores y 0 omitidas; BUILD SUCCESS**, con Java 17 y PostgreSQL desechable. Incluye 11 pruebas nuevas de límites, concurrencia, sesiones y carga de cuerpos. [Secret hygiene](https://github.com/jose6668/Software_MSPI-back/actions/runs/42527468679): correcto.

Esta evidencia corresponde al commit indicado. Los commits posteriores conservan sus comprobaciones propias en el PR. No representa una medición de capacidad ni un ataque de carga en el equipo del usuario.
