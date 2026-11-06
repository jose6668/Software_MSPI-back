## HU y resultado

HU-xxx — Título

Ficha: `docs/hu/HU-xxx.md`

Explica el problema concreto y el comportamiento resultante. Incluye referencias RF/CU/PER/CAL/TR/DMP/ARQ aplicables.

## Alcance

- Cambios necesarios para esta HU:
- Fuera de alcance y pendientes:

## Criterios y verificación

| Criterio/escenario | Prueba o revisión | Resultado y evidencia |
| --- | --- | --- |
| CA-01 | | Pendiente |

Indica comandos, Java/PostgreSQL/entorno utilizados, resultados y enlace a CI. Una prueba bloqueada, omitida o no ejecutada permanece pendiente. Distingue pruebas de política de escenarios integrales.

## Datos, seguridad y contrato

Describe migraciones y recuperación, permisos/aislamiento, revisión e historial, contrato OpenAPI y compatibilidad que afecte esta HU. Justifica lo que no aplique. No incluyas secretos ni datos reales.

## Rúbrica y revisión

- [ ] La base del PR es exactamente `Develop` y el alcance corresponde a una HU.
- [ ] La ficha enlaza requisitos y todos sus criterios tienen evidencia satisfactoria.
- [ ] R1 a R8 cumplen la rúbrica; cualquier N/A está justificado.
- [ ] Compilación y comprobaciones aplicables pasan con Java 17.
- [ ] Las verificaciones de CI requeridas terminaron satisfactoriamente.
- [ ] Revisé el diff; no hay cambios ajenos, secretos ni debilitamiento de seguridad.
- [ ] Las limitaciones y decisiones pendientes están identificadas sin afirmar comportamientos no probados.

Si falta evidencia requerida, mantener como borrador. Abrir este PR no autoriza fusionarlo automáticamente.
