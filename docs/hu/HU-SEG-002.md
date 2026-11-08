# HU-SEG-002 — Prevención de secretos en el repositorio backend

Estado: implementación local; verificación/publicación en curso.
Rama: `feat/HU-SEG-002-prevencion-secretos`.
Base Develop: `814753bbf7786859c1385a9ad3585d0f66648d39`.
PR: pendiente de publicación.

Como equipo, queremos impedir publicar accidentalmente configuración privada y detectar secretos en cada entrega para que conocer el repositorio no conceda credenciales de acceso.

Trazabilidad: H-01 del diagnóstico, SEC-001/002 y P01/P02; ASVS 5.0.0 V13.3.1 y prevención en CI. No se declara completa la gestión operativa de secretos H-11.

## Implementación

`.gitignore` raíz, `.env.example` sin secretos, comprobación de archivos/plantillas y workflow Secret hygiene con Gitleaks fijado y verificado. [Operación y límites](../SECRETOS-Y-CONFIGURACION.md).

No cambia código de negocio, API, base, versión de esquema, credenciales del equipo, dependencias Java o CI de regresión existente. No modifica el trabajo de otros colaboradores ni vuelve obligatorio un check por configuración administrativa de GitHub.

## Aceptación y evidencia

| Criterio | Evidencia exigida |
| --- | --- |
| CA-01. Configuración privada excluida | Archivo local y anidado ignorado; plantilla pública versionable |
| CA-02. No se elude con git add -f | Repositorio temporal con .env forzado: verificador devuelve rechazo |
| CA-03. Plantillas sin secretos | Valores privados/URL con credencial y variables VITE sensibles rechazadas sin publicar su valor |
| CA-04. Detector real e historial | Comprobante sintético detectado en archivos y después de retirarlo del último commit, con reportes redactados |
| CA-05. Escaneo de esta entrega | Archivos actuales e historial disponibles sin hallazgos no revisados |
| CA-06. CI y entrega | Workflow pasa sobre el último commit y PR hacia Develop; resultados y enlaces registrados |

Rúbrica: R1–R3 aplican a alcance/política y entrega con privilegios mínimos; R4/R5 N/A porque no cambia persistencia ni API; R6/R7 requieren pruebas de esta automatización, no una nueva ejecución de lógica Java; R8 se completa con PR y CI. No se declaran pruebas Maven o Docker ejecutadas por esta HU.

## Verificación local

El 8 de noviembre de 2026 pasaron el verificador y sus pruebas negativas con Python 3.13 y Gitleaks 8.30.1. Se comprobó el rechazo de .env forzado, plantillas privadas y credenciales en URL, y la detección/redacción del comprobante sintético actual e histórico. Los escaneos de archivos e historial disponibles de esta rama terminaron con salida 0 y cero hallazgos. `git diff --cached --check` pasó. CI queda pendiente hasta publicar el PR.
