# Membresías y permisos por empresa

HU-008 implementa RF-04/05, CU-05 y la base de PER-05/06, PA-01/02/03/06/08/14/15/16/18 y AT-05/06/14. La identidad proviene de la sesión de HU-007 y el aislamiento físico conserva las credenciales limitadas de HU-003/004.

## Selección y autorización

- `GET /api/v1/mis-empresas?pagina=0&tamano=20` devuelve la página de empresas con membresía activa y versión estructural compatible. El orden es nombre y UUID. El máximo técnico de página es 100. La respuesta incluye `empresas`, `pagina`, `tamano` y `hayMas`.
- `GET /api/v1/empresas/{empresaId}/contexto` vuelve a comprobar la membresía. Su proyección mínima contiene UUID, nombre, zona horaria y roles locales. La atribución AP no concede acceso por sí sola.
- Cada solicitud usa el UUID de su ruta. Seleccionar B en una pestaña no cambia el destino de una solicitud explícita a A. El módulo de Identidad valida la sesión y la cuenta habilitada mediante su API pública. Empresas consulta la empresa, membresía y roles vigentes sin copiarlos en la sesión.
- Los endpoints de selección consultan el registro común. Una operación de negocio debe indicar su permiso al llamar a `TenantUnitOfWork.execute(actorId, empresaId, permiso, operación)`. El actor debe coincidir con la identidad de sesión. Antes de abrir el pool se consulta la autorización y se valida el registro. Luego se comprueba el propietario al obtener la conexión técnica. [Aislamiento JPA](AISLAMIENTO-JPA.md).
- Los roles se combinan dentro de la membresía elegida. AU concede lectura operativa, GS puede conceder edición y RA por sí solo recibe el contexto mínimo. Las vistas de acciones asignadas y sus evidencias se definen con sus operaciones. Revocación, suspensión, incompatibilidad y retiro de roles se reflejan en la siguiente solicitud.
- Los adaptadores deben resolver identificadores de objetos dentro de la unidad empresarial y validar las restricciones específicas antes de cambiar datos. Para decidir una entrega, exigir permiso de revisión y llamar a `requireIndependentReviewer` con el responsable registrado en esa entrega. La restricción se aplica a aprobación y devolución. La consulta autorizada conserva sus propias reglas.

Las rutas productivas de esta HU son las dos consultas anteriores. Inventario, escritura, revisión de acciones y cambio de permisos se prueban mediante controladores y datos exclusivos de tests. AT-05 queda comprobado como política; la transición y su persistencia se completan en HU-024. [Contrato OpenAPI](../mspi/src/main/resources/openapi/v1.yaml).

## Modelo y operación

Flyway común V4 introduce `membresias`, `roles_empresariales`, `membresia_roles` y `atribuciones_plataforma`. La restricción única por empresa y usuario impide duplicados. Los roles empresariales permitidos son AE, GS, RA y AU. AP se registra como atribución de plataforma.

Una membresía activa debe referenciar al menos un rol asignado. El campo técnico `rol_ancla` identifica un elemento del conjunto mediante una FK diferida hasta el final de la transacción. El ancla no tiene prioridad de permisos. Al quitar el rol ancla, el escritor debe elegir otro rol vigente en la misma transacción. La invitación y la administración de membresías se implementan en HU-009/011. La regla concurrente del último AE se verifica con esos escritores. [Restricciones de PostgreSQL](https://www.postgresql.org/docs/17/ddl-constraints.html).

La clave única `(empresa_id,id)` prepara las referencias compuestas de MOD-03. Una tabla exclusiva de pruebas demuestra que una referencia de A a una membresía de B se rechaza. Cada dominio que guarde responsables deberá declarar y probar su FK local y el permiso técnico de referencia aplicable. El runtime común puede leer las columnas de autorización y no puede modificar los conjuntos. Los runtimes empresariales no leen el directorio común. La configuración de pools empresariales continúa siendo explícita y puede quedar deshabilitada cuando solo se operen las consultas del registro.
