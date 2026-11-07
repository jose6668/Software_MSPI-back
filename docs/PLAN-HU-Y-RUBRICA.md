# Backend MSPI: historias de usuario y rúbrica de entrega

Fecha: 6 de noviembre de 2026.

Estado: plan de trabajo inicial. La forma de trabajo por HU y un PR hacia `Develop` por cada HU terminada fue solicitada por el usuario. El desglose y los criterios de este archivo son una propuesta de ejecución; no aprueban políticas, tecnologías o parámetros pendientes en la documentación central.

## 1. Base y alcance

Fuente normativa: documentos 01 a 08 del repositorio documental `Software_MSPI-`, revisión `964249cbd00d13c3fde06e4451f7948d583531b8`. APR-08 establece: diagnóstico → estructura modular → migraciones y aislamiento → sesión y permisos → primera funcionalidad completa. Pruebas y CI acompañan cada entrega.

Referencias locales mientras se conserve la distribución actual de repositorios:

- [Requerimientos y aprobaciones](../../Software_MSPI-/01-parametros-y-requerimientos.md).
- [Permisos](../../Software_MSPI-/02-matriz-de-permisos.md).
- [Casos de uso](../../Software_MSPI-/03-casos-de-uso.md).
- [Cálculos](../../Software_MSPI-/04-reglas-de-calculo-y-valoracion.md).
- [Diccionario](../../Software_MSPI-/05-diccionario-de-datos-y-validaciones.md).
- [Estados](../../Software_MSPI-/06-estados-y-transiciones.md).
- [Modelo y aislamiento](../../Software_MSPI-/07-modelo-de-datos-y-aislamiento-por-esquemas.md).
- [Arquitectura y APR-08](../../Software_MSPI-/08-arquitectura-y-decisiones-tecnicas.md).

El documento 09 y Panorama orientan la experiencia de usuario. Sus datos ficticios, componentes, tamaños de página y simuladores no definen contratos ni permisos del backend. Este archivo no modifica la documentación central ni sustituye sus decisiones.

Se conserva Java 17, Spring Boot, Maven, YAML, `software.mspi`, Spring Modulith, JPA, PostgreSQL, Flyway, sesión de servidor, cookie protegida, CSRF y REST `/api/v1`. No se cambia un principio aprobado por una solución más fácil de implementar.

## 2. Unidad de trabajo: una HU y un PR

1. Seleccionar una HU lista y acordar su alcance concreto usando la plantilla de la sección 7. Una historia técnica expresa un resultado verificable para el equipo o la operación.
2. Comprobar rama, estado del árbol y cambios existentes. Actualizar la referencia de `Develop` cuando haya conectividad, sin sobrescribir trabajo local. No implementar sobre `main` ni confirmar directamente en `Develop`.
3. Crear una rama desde la revisión vigente de `Develop`: `feat/HU-001-base-java17-ci`, `fix/HU-xxx-descripcion` o `docs/HU-xxx-descripcion`, según el trabajo. Los ejemplos no son ramas ya creadas.
4. Implementar únicamente la HU y las tareas técnicas indispensables para aceptarla. Si se amplía el objetivo, dividir antes de cerrar; no incluir otra HU funcional independiente en el mismo PR.
5. Ejecutar sus pruebas y verificaciones aplicables, inspeccionar el diff y registrar resultados reales. Corregir fallos atribuibles a la HU.
6. Evaluar la rúbrica. Si queda un bloqueo, conservar la HU en curso o bloqueada y registrar la causa. Se puede abrir un PR borrador para revisión, pero no declararlo listo.
7. Al terminar la HU, confirmar y publicar su rama y abrir **un PR con base exacta `Develop`**, usando la plantilla del repositorio. Este es el flujo solicitado para las siguientes HU; preparar este plan no publica cambios automáticamente.
8. Incorporar observaciones en el mismo PR. La apertura del PR no autoriza su aprobación o fusión automática. La revisión y la decisión de fusionar se mantienen explícitas.
9. Tras la fusión, registrar el PR, commit y evidencia; preparar la siguiente rama desde `Develop` actualizado. Mantener integración continua en verde.

Un PR incluye código, pruebas, migraciones y contrato/documentación local necesarios para esa HU. Las pruebas no requieren otro PR. Un fallo ajeno preexistente se documenta y se resuelve o se delimita antes de afirmar que la historia pasa sus verificaciones.

## 3. Estados y condición de inicio

| Estado | Significado |
| --- | --- |
| Propuesta | Historia identificada; no se han cerrado alcance o dependencias |
| Lista | Alcance, criterios, dependencias y decisiones necesarias resueltos |
| En curso | Implementación o verificación en progreso |
| Bloqueada | Existe un impedimento concreto registrado; no equivale a terminada |
| Lista para revisión | Rúbrica cumplida y PR abierto hacia `Develop` |
| Cambios solicitados | El PR requiere correcciones; se continúa en la misma rama |
| Integrada | PR revisado y fusionado en `Develop`, con verificaciones satisfactorias |

Para comenzar una HU deben existir: actor y objetivo, referencias RF/CU/PER/CAL/TR/ARQ/DMP aplicables, criterios comprobables, alcance excluido, dependencias y decisiones necesarias. No se exige volver a aprobar principios ratificados por APR-08. Los parámetros todavía abiertos se concretan antes de implementar el comportamiento que dependa de ellos.

## 4. Rúbrica de cierre

Cada dimensión se evalúa con evidencia: **0 = ausente o incumplida; 1 = parcial, sin verificar o bloqueada; 2 = completa y verificada**. `N/A` exige justificar por qué no aplica al alcance. Una puntuación total no compensa un incumplimiento.

**Una HU queda lista para revisión únicamente cuando todas las dimensiones aplicables tienen 2 y no quedan bloqueos de aceptación.** No se exige un porcentaje arbitrario de cobertura de código. Importa probar reglas, límites y riesgos de la historia. Una revisión documental no requiere pruebas de ejecución que no le correspondan.

| Dimensión | Para obtener 2 | Evidencia esperada |
| --- | --- | --- |
| R1. Alcance y trazabilidad | Cumple todos sus criterios; distingue lo aprobado de lo pendiente; el PR corresponde a una HU | Ficha HU, referencias y correspondencia criterio → prueba/revisión |
| R2. Arquitectura modular | Respeta propiedad de datos y contratos públicos, sin ciclos ni acceso a internos ajenos | Verificación Modulith cuando se afecte estructura o dependencias; revisión del diff |
| R3. Seguridad y aislamiento | Valida identidad, empresa, permisos y objeto en el alcance aplicable; conserva CSRF/sesión y privilegios mínimos | Casos permitidos y rechazados, pruebas SQL/HTTP según la capa implementada |
| R4. Datos e historial | Migraciones, integridad, transacciones, revisión e historial coherentes; no sobrescribe versiones cerradas | Migración reproducible y pruebas de restricciones, rollback o concurrencia pertinentes |
| R5. Contrato API | DTO y validaciones coherentes, errores uniformes, empresa explícita, comandos y OpenAPI cuando corresponda | Contrato actualizado y pruebas HTTP; ninguna entidad JPA expuesta como contrato |
| R6. Pruebas | Escenarios de aceptación aplicables ejecutados satisfactoriamente con datos ficticios | Comandos, entorno, resultados y reportes; PostgreSQL real para aislamiento |
| R7. Construcción y CI | Compila con Java 17 y pasa las comprobaciones exigidas a la HU, incluyendo CI | Ejecución local y enlace al workflow/check correspondiente |
| R8. Revisión y entrega | Diff revisable, sin secretos ni cambios ajenos; PR completo con límites y resultado explicados | PR hacia `Develop`, ficha actualizada, riesgos y pendientes identificados |

Antes de abrir el PR, R8 se revisa sobre su contenido preparado; se completa al abrirlo y comprobar su base y referencias. Si CI solo puede ejecutarse tras publicar la rama, el PR permanece en borrador hasta obtener evidencia satisfactoria.

Reglas obligatorias:

- Una prueba no ejecutada, omitida, deshabilitada o bloqueada no cuenta como superada.
- H2 o mocks no demuestran permisos PostgreSQL ni aislamiento entre esquemas.
- Los arneses previos a autenticación son pruebas internas; no se publican como endpoints empresariales sin protección.
- No cerrar escenarios integrales con evidencia de una sola capa. AT-05 se comprueba como política durante sesión/permisos y se completa integralmente con el flujo de acciones.
- No debilitar seguridad para hacer pasar pruebas ni congelar permisos empresariales en la sesión sin revalidación.
- Una migración fallida no habilita una empresa incompatible; su recuperación forma parte de la aceptación aplicable.
- Una HU futura puede ampliar cobertura; no se puede diferir un criterio necesario para declarar completa la HU actual.

## 5. Backlog propuesto y secuencia

Las fichas registran el estado y la evidencia de [HU-001](hu/HU-001.md), [HU-002](hu/HU-002.md), [HU-003](hu/HU-003.md), [HU-004](hu/HU-004.md), [HU-005](hu/HU-005.md) y [HU-006](hu/HU-006.md). El resto permanece en estado **Propuesta**. Los números identifican historias y el orden depende de sus prerrequisitos.

### Etapa inicial: estructura y entorno reproducible

| HU | Historia y resultado | Criterios de aceptación mínimos | Referencias y dependencias |
| --- | --- | --- | --- |
| HU-001 | Como desarrollador, quiero construir y verificar el backend con Java 17 para obtener una base reproducible | Wrapper operativo; fuentes y pruebas compilan con 17; `contextLoads` ejecutada con PostgreSQL de prueba sin secretos reales; CI ejecuta la comprobación equivalente y conserva resultados | AT-01; ARQ-14. Resolver caché Surefire, acceso a dependencias y disponibilidad Docker; fijar PostgreSQL de pruebas. No deshabilitar JPA para ocultar un fallo de arranque |
| HU-002 | Como equipo, quiero límites funcionales verificables para desarrollar el monolito sin acoplamientos indebidos | Preparar los doce límites aprobados y contratos necesarios; `gestion` coordina; verificación detecta ciclos y accesos internos; no inventar funcionalidad para llenar paquetes vacíos | ARQ-07; AT-02. Depende de HU-001 |

### Migraciones y aislamiento antes de ampliar funcionalidades

| HU | Historia y resultado | Criterios de aceptación mínimos | Referencias y dependencias |
| --- | --- | --- | --- |
| HU-003 | Como operador técnico, quiero migraciones separadas por ámbito para preparar estructuras con privilegios controlados | Esquema común y estructura empresarial mínima versionados; fila propietaria y pertenencia controlada; credenciales ordinarias separadas de migración; sin DDL automático Hibernate; instalación desde vacío verificada | DMP-01/02/04/07; ARQ-13; MOD-01/03. Depende de HU-002 y diseño físico mínimo |
| HU-004 | Como empresa, quiero que mi acceso de datos esté aislado para impedir consultas o cambios de otra empresa | Dos empresas ficticias; rechazo SQL de lectura/escritura cruzada; rechazo de `empresa_id` ajeno; JPA separa acceso común/empresarial y fija empresa por transacción; alternancia, reutilización de conexiones y errores sin contaminación | SEG-01 a SEG-10 aplicables; AIS-02/03/04/09/20; AT-03/04. Depende de HU-003 |
| HU-005 | Como operador técnico, quiero recuperar preparación y migraciones fallidas para evitar empresas parcialmente operativas | Registrar pasos y fallos; reintentar sobre la misma empresa sin duplicarla; bloquear incompatibles; verificar antes de declarar versión instalada; probar fallos y recuperación sin afectar la segunda empresa | DMP-07; AIS-17/18; AT-09. Depende de HU-003/004. Arnés técnico; activación productiva e invitación se completan en HU-010 |

### Sesión, autorización y administración

| HU | Historia y resultado | Criterios de aceptación mínimos | Referencias y dependencias |
| --- | --- | --- | --- |
| HU-006 | Como consumidor autorizado, quiero contratos REST consistentes para integrar las operaciones del backend | Convenciones `/api/v1`, DTO, errores y correlación; validaciones y revisión esperada donde apliquen; OpenAPI de las operaciones existentes; no exponer secretos, SQL ni entidades | ARQ-10; DVAL-01/05/07/13; AT-13. Depende de HU-002; acompaña las primeras operaciones HTTP, sin adelantar acceso empresarial |
| HU-007 | Como usuario, quiero iniciar y cerrar sesión para acceder de forma segura | Credenciales verificadas según mecanismo concretado; sesión de servidor, cookie HttpOnly y Secure en HTTPS; CSRF en mutaciones incluidas autenticación/salida según integración; logout invalida sesión; expiración y rechazos genéricos probados | RF-02; CU-03; TR-SES; AT-14. Depende de HU-004/006; políticas de identidad, sesión e intentos definidas |
| HU-008 | Como miembro, quiero operar solo en empresas y con permisos vigentes para evitar acceso indebido | Empresas propias operables; empresa explícita por solicitud; roles combinados localmente; AP sin lectura automática; AU solo no edita; revocación/suspensión surten efecto en solicitud posterior; política contra aprobar/devolver entrega propia | RF-04/05; CU-05; PER; PA-01/02/03/06/14/16/18; AT-06/14. Depende de HU-007. AT-05 integral queda pendiente hasta HU-024 |
| HU-009 | Como AE y destinatario, quiero emitir y aceptar invitaciones para incorporar miembros sin registro público | Destinatario verificado; roles ofrecidos fijos; token protegido de un uso; vencida/revocada/usada rechazada; consumo concurrente único; enlace antiguo no cambia membresía existente | RF-03; CU-07; TR-INV/MEM; AIS-08. Depende de HU-008 y canal/vencimientos definidos; puede probarse sobre empresas de fixtures |
| HU-010 | Como AP, quiero crear, suspender y reactivar empresas para administrar el servicio sin consultar su contenido | Alta real por pasos utiliza HU-005; invitación inicial AE recuperable; no activa si preparación incompleta; suspensión bloquea solicitudes posteriores; reactivación exige compatibilidad; solo metadatos de plataforma | RF-01/05; CU-01/02; TR-EMP; AT-09. Depende de HU-005/008/009; mecanismo inicial de atribución AP definido |
| HU-011 | Como AE, quiero administrar membresías y configuración para mantener mi empresa autorizada y operable | Roles empresariales válidos y no vacíos; no asigna AP; no elimina último AE incluso bajo concurrencia; cambios vigentes en siguiente solicitud; configuración no acepta esquema ni estado técnico; historial y selector mínimo | RF-03/05; CU-06/08; MOD-10; PA-07/17. Depende de HU-008/009. Readmisión continúa fuera de alcance hasta especificarse |
| HU-012 | Como titular, quiero recuperar mi acceso mediante verificación para restablecer credenciales sin ampliar permisos | Respuesta sin enumerar cuentas; canal verificado; comprobante protegido con vencimiento y consumo único; invalidación de sesiones anteriores; no reactiva membresías ni empresas | RF-22; CU-04; TR-REC/SES; DAT-17. Depende de HU-007 y políticas/canal definidos |

### Funcionalidades operativas

La primera funcionalidad completa aún no está seleccionada en APR-08. Se recomienda **CU-09, procesos y responsables**, porque entrega un flujo acotado y prepara las referencias necesarias para activos. HU-013 es esa candidata; la recomendación no se registra como aprobación del caso de uso. Activos y clasificación continúan en HU-014/015.

| HU | Historia y resultado | Criterios de aceptación mínimos | Referencias y dependencias |
| --- | --- | --- | --- |
| HU-013 | Como AE o GS, quiero mantener procesos y responsables para referenciar información válida de mi empresa | Crear, editar, consultar y archivar según permisos; referencias locales; archivo conserva historia e impide nuevas asignaciones; AU consulta; RA solo contexto permitido; conflictos de revisión detectados | RF-06; CU-09; DD-09/10; TR-ARC; PER-12/13. Depende de HU-005/006/008; contratos y auditoría de sus operaciones incluidos |
| HU-014 | Como AE o GS, quiero mantener activos para disponer de un inventario empresarial trazable | Campos mínimos y referencias locales; código único local incluso archivado; mismo código posible en otra empresa; búsqueda/paginación; archivo/restauración con motivo; no edición archivada; AU consulta y RA no recibe inventario | RF-07/19; CU-10/12; DD-11; TR-ARC; DAT-01/02/09. Depende de HU-013 y normalización/catálogos necesarios definidos |
| HU-015 | Como gestor, quiero clasificar activos para conocer su criticidad sin inventar valores faltantes | C/I/D solo 1/3/5; máximo si están completas; faltante deja pendiente; datos condicionales coherentes; versión, autor e historial conservados | RF-08; CU-11; CAL-ACT; VAL-01 a VAL-05/35. Depende de HU-014 y metodología/campos aplicables definidos |
| HU-016 | Como miembro con consulta, quiero catálogos y metodología versionados para usar referencias inequívocas | Versiones publicadas y origen identificado; lectura autorizada mínima común; sin edición empresarial; históricos no cambian; no inventa equivalencias entre ediciones | RF-09; CU-13; DD-07/08; MOD-05; AIS-10/12. Depende de HU-004/008 y contenido/versiones validados |
| HU-017 | Como AE o GS, quiero diligenciar evaluaciones para registrar resultados y pendientes separados | Nuevo borrador no sobrescribe anterior; alcance/versiones fijos; notas discretas; cero, pendiente y N distintos; fundamento y hallazgos; media por control, avance y cláusulas separados, sin PHVA ponderado | RF-10/11; CU-14/15; CAL-EVA; VAL-06 a VAL-10/30/31/32/36. Depende de HU-013/016 |
| HU-018 | Como AE o GS, quiero revisar y cerrar evaluaciones para conservar diagnósticos reproducibles | Sin cierre con pendientes o evidencia insuficiente; devolución con motivo; cierre inmutable; corrección crea revisión vinculada y confirma heredadas; cierre de evaluación propia permitido | RF-11; CU-16; TR-EVA; ET-08/09/10/27. Depende de HU-017/025 y política de aplicabilidad/evidencia por criterio resuelta |
| HU-019 | Como gestor, quiero documentar implementaciones y verificaciones para distinguir aplicación y efectividad de un control | Implementación, versión y verificación separadas; evidencias pertinentes; cambios conservan versiones y señalan dependencias; ningún descuento automático por nota, tipo o modo | RF-14; CU-19; DD-16/18; TR-CTL/VER. Depende de HU-013/016/025 y rúbricas/evidencia aplicables definidas |
| HU-020 | Como gestor, quiero registrar y analizar riesgos para gestionar amenazas sobre activos de mi empresa | Activo principal válido; amenaza, vulnerabilidad y responsable; referencias locales; códigos únicos; estado y nivel separados; inherente categórica por matriz versionada; faltantes pendientes | RF-12/13; CU-17/18; DD-17/19; CAL-RIE-01/03; VAL-19. Depende de HU-014/016 y rúbricas aplicables definidas |
| HU-021 | Como gestor, quiero valorar el residual conjunto para justificar el riesgo tras considerar controles | Categorías justificadas; misma base inherente y riesgo; versiones/verificaciones consideradas fijas; sin mitigación explícita cuando proceda; sin descuentos; cambios generan avisos de revisión y preservan historia | RF-13/14; CU-18/19; CAL-RIE-06; MOD-06/07; AIS-11/12. Depende de HU-019/020 |
| HU-022 | Como AE o gestor autorizado, quiero decidir tratamiento y cierre para conservar decisiones justificadas | Solo AE acepta; valoración vigente y próxima revisión; aceptación no cierra; cierre desde seguimiento con valoraciones vigentes y acciones resueltas; nuevo riesgo posterior conserva original | CU-20/21; RN-09; TR-RIE; ET-14/15/16/28. Depende de HU-021/024 y criterios de cierre definidos |
| HU-023 | Como gestor, quiero crear y asignar acciones para convertir riesgos o hallazgos en trabajo verificable | Exactamente un origen local; responsable elegible AE/GS/RA; plazo y resultado esperado; cambios con motivo; reasignación retira acceso anterior; vencimiento separado del estado; contexto RA mínimo | RF-15; CU-22; DD-21; PER-29/30/31; DAT-06/07. Depende de HU-020 o HU-017 según origen, y HU-011; detalle de contexto mínimo definido |
| HU-024 | Como responsable y revisor autorizado, quiero entregar y revisar acciones para comprobar resultados con separación de personas | Solo asignado registra avance/entrega; evidencia exigida disponible; entrega congelada; AE/GS distinto aprueba o devuelve con motivo; dos revisores no duplican decisión; terminales no reabren; no altera residual automáticamente | RF-15/16; CU-23/24; TR-ACC; PA-05/15; ET-17/18/19/20; AT-05 integral. Depende de HU-023/025 |
| HU-025 | Como usuario autorizado, quiero aportar y consultar evidencias para sustentar registros sin exponer archivos ajenos | Archivo privado o referencia permitida; contenido/tamaño validados; carga parcial no disponible; descarga autoriza objeto y empresa; RA solo evidencia propia/contexto autorizado; referencia no dispara descarga externa; pruebas de fallos de publicación | RF-17; CU-25; TR-EVI; AIS-15/22; AT-11. Depende de HU-008 y al menos un destino implementado. Ampliar adaptadores y pruebas en las HU de nuevos destinos; límites/formatos/corrección definidos antes de habilitarlos |

### Consulta y preparación operativa

| HU | Historia y resultado | Criterios de aceptación mínimos | Referencias y dependencias |
| --- | --- | --- | --- |
| HU-026 | Como AE, GS o AU, quiero consultar resultados para priorizar seguimiento de mi empresa | Agregados concilian con detalle y filtros; riesgos distintos por ID; pendientes/revisión separados; acciones vencidas sin duplicar estados; RA no recibe panel general; sin etiquetas metodológicas pendientes | RF-18/19; CU-26; DD-26; PER-36. Depende de datos de las HU operativas correspondientes |
| HU-027 | Como usuario con consulta, quiero un reporte básico para presentar resultados con contexto y metodología | Contrato para vista imprimible con empresa, corte, filtros, versiones y pendientes; mismos permisos; concilia con panel; excluye actividad administrativa y no afirma certificación | RF-20; CU-27; PER-37; AT-13. Depende de HU-026. Presentación imprimible corresponde al frontend y no se modifica con esta HU backend |
| HU-028 | Como AE, quiero consultar actividad para conocer cambios y decisiones de mi empresa | Historial solo de empresa autorizada; filtros/paginación; GS/RA/AU no acceden a registro administrativo; sin edición/borrado ni secretos; avances operativos no se confunden con este registro | RF-21; CU-28; DD-25; PER-38. Depende de HU-008 y eventos ya generados desde cada mutación |
| HU-029 | Como operador, quiero restaurar un entorno completo para demostrar recuperación coherente | Restaurar común, empresas, catálogos/versiones y archivos en entorno separado; comprobar integridad y aislamiento; documentar procedimiento y resultados; no prometer restauración selectiva ni RPO/RTO sin aprobación | DMP-08; AIS-19; AT-12; criterio mínimo de v1. Depende de almacenamiento y entorno elegidos, HU-025 y datos representativos |

Las dependencias evitan ciclos: HU-025 puede comenzar con respuestas de evaluación de HU-017, sin exigir cierre HU-018; HU-019 incorpora después su destino de evidencia. HU-023 crea acciones; HU-024 completa ejecución y revisión. HU-022 cierra riesgos después de disponer de acciones resueltas. El orden numérico no obliga a implementar HU-022 antes de HU-024/025.

La escritura de auditoría, revisión esperada, autorización y pruebas de aislamiento pertenecen a cada HU que las requiera. HU-028 añade su consulta administrativa; no posterga la escritura del historial hasta esa etapa.

## 6. Decisiones pendientes y sus historias afectadas

| Decisión | Antes de habilitar |
| --- | --- |
| Versión PostgreSQL, entorno Docker/CI y compatibilidad real | HU-001 y pruebas de base de datos |
| IDs, tipos, normalización y límites del modelo físico mínimo | HU-003 y cada entidad que los utilice |
| Privilegios, conexiones acotadas, resolución JPA y gestión de secretos | HU-003/004 |
| Recuperación concreta de migraciones y preparación | HU-005 y alta real HU-010 |
| Biblioteca o mecanismo OpenAPI compatible y formato de errores | HU-006 |
| Identidad local/proveedor, credenciales, sesiones, intentos y cookies por entorno | HU-007 |
| Canal, verificación, duraciones de invitación y recuperación | HU-009/010/012 |
| Rúbricas, catálogo validado, N en cláusulas, evidencia y vigencia por criterio | HU-016 a HU-022 según comportamiento |
| Contexto mínimo RA, criterios de entrega y evidencia exigida | HU-023/024 |
| Almacenamiento privado, formatos, límite exacto, cuotas y corrección/retención | HU-025 y preparación operativa aplicable |
| Infraestructura, coherencia de copias y objetivos operativos | HU-029 |

No se habilitan cierres con criterios inventados. PHVA ponderado, mitigación automática, NIST independiente, importación Excel, reportes avanzados y registro público siguen fuera del alcance aprobado de v1.

## 7. Plantilla de ficha HU y evidencia

Crear una ficha en `docs/hu/HU-xxx.md` al tomar una historia. Esta plantilla no implica crear todas las fichas anticipadamente.

```markdown
# HU-xxx — Título

Estado: Propuesta / Lista / En curso / Bloqueada / Lista para revisión / Cambios solicitados / Integrada
Rama:
Base Develop (commit):
Documentación de referencia (commit):
PR hacia Develop:

Como [actor], quiero [capacidad], para [beneficio].

## Trazabilidad
RF/CU/PER/CAL/TR/DMP/ARQ y escenarios aplicables.

## Alcance
Incluye:
Fuera de alcance:
Dependencias:
Decisiones necesarias y su estado:

## Criterios de aceptación
- CA-01. Dado [...], cuando [...], entonces [...].
- CA-02. Caso de rechazo, límite o conflicto pertinente.

## Tareas
- Cambios mínimos de implementación, contrato, migración e historial necesarios.
- Pruebas y CI pertinentes.

## Evidencia
| Criterio/escenario | Prueba o revisión | Comando/entorno/commit | Resultado y reporte |
| --- | --- | --- | --- |
| CA-01 | | | Pendiente |

## Rúbrica
| Dimensión | 0 / 1 / 2 / N/A | Evidencia o justificación |
| --- | --- | --- |
| R1 | | |
| R2 | | |
| R3 | | |
| R4 | | |
| R5 | | |
| R6 | | |
| R7 | | |
| R8 | | |

## Riesgos, bloqueos y pendientes
Indicar efectos y condiciones para resolverlos. No convertirlos en pruebas superadas.

## Resultado de revisión
Observaciones resueltas, PR, commit de fusión y comprobaciones posteriores aplicables.
```

## 8. Punto de partida comprobado

Diagnóstico del backend sobre `Develop`, commit `01afba1397bdbe821dec79102a1ef8947511bc9d`:

- Proyecto inicial: clase de arranque, YAML básico y una prueba `contextLoads`; no hay funciones empresariales implementadas.
- Java 17.0.12 utilizable; Java 18 era el predeterminado de la terminal.
- Recompilación de fuente y prueba con `release 17` comprobada mediante Maven local 3.9.16.
- Wrapper configurado para Maven 3.10.0; su descarga quedó bloqueada por resolución de red en el entorno del diagnóstico.
- Ejecución de JUnit bloqueada al cargar Surefire 3.5.6: falta su POM local y `SurefireReportParameters`. `contextLoads` no se declara superada.
- Docker CLI instalada; el motor Docker Desktop no respondía. Testcontainers no se ejecutó.
- Árbol de dependencias obtenido con plugin local 3.6.1. La compilación no demuestra aislamiento ni arranque PostgreSQL.

La documentación `964249c` corrige las tres precisiones revisadas: aprobar/devolver entrega propia frente a consultarla; AT-05 de política frente a integral; escenarios AIS en documento 07 y AT en documento 08.

HU-001 a HU-005 quedaron integradas mediante PR #1 a #5. **HU-006** establece los contratos REST, los errores y las validaciones. Después de integrarla corresponde **HU-007**, sobre inicio y cierre de sesión. Consultar la [ficha de HU-006](hu/HU-006.md).

La elaboración original de este archivo y la plantilla de PR fue preparación de trabajo, sin implementar HU ni crear ramas, commits o PR. La ejecución posterior se registra en la ficha de cada historia.
