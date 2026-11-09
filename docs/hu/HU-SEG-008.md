# HU-SEG-008. Inspección antimalware y cuarentena

Como responsable de seguridad, quiero que los archivos adjuntos se inspeccionen antes de publicarlos o descargarlos para evitar distribuir evidencia que el motor detecte como peligrosa.

Rama `feat/HU-SEG-008-antimalware-cuarentena` hacia `Develop`. Trazabilidad: H-05, SEC-020, prueba P20, ASVS 5.0 V5.4.3. Implementación y [puesta en marcha](../INSPECCION-EVIDENCIAS.md). OpenAPI 1.15.0, esquema empresarial V8 y común V13.

| Criterio | Verificación |
| --- | --- |
| Un archivo detectado no se publica ni consume cuota | Prueba HTTP con detección simulada y revisión del borrador |
| Un resultado incompleto, motor saturado o catálogo viejo no habilita el archivo | Pruebas TCP y HTTP con respuestas erróneas, ausencia de respuesta, caducidad y capacidad |
| Un archivo antiguo exige escaneo; la cuarentena sobrevive a la recuperación | Prueba HTTP sobre evidencia pendiente de una evaluación en revisión, auditoría y bloqueo SQL |
| El motor reconoce contenido de prueba por el protocolo real | Contenedor ClamAV con una firma inocua en el catálogo de pruebas |
| Se conserva el aislamiento entre empresas y los controles de archivo de HU-025 | Suite de autorización y migraciones con PostgreSQL |
