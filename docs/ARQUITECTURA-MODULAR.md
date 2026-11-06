# Estructura modular del backend

HU-002 implementa los límites de ARQ-07 y AT-02, según APR-06 y APR-08 del documento central 08, revisión `964249c`. Se conserva un proyecto Maven, un JAR y el paquete base `software.mspi`.

## Módulos

| Paquete bajo `software.mspi` | Responsabilidad |
| --- | --- |
| identidad | Cuenta, autenticación, sesión y recuperación |
| empresas | Empresas, membresías, roles e invitaciones |
| catalogos | Catálogos y metodologías versionados |
| activos | Procesos, responsables, inventario y clasificación |
| evaluaciones | Evaluaciones, respuestas y hallazgos |
| controles | Implementaciones y verificaciones de controles |
| riesgos | Riesgos, valoraciones, tratamiento y aceptación |
| acciones | Acciones de mejora, entregas y revisión independiente |
| evidencias | Archivos privados y referencias documentales |
| auditoria | Atribución y registro de eventos por ámbito |
| reportes | Proyecciones autorizadas y reporte básico |
| gestion | Coordinación de casos de uso entre módulos |

Los paquetes están delimitados mediante `@ApplicationModule`; todos son cerrados. Su declaración no implica que sus funciones ya estén implementadas.

## Contratos e internos

Cada módulo tiene esta estructura mínima:

```text
software.mspi.<modulo>
  package-info.java         límite y dependencias permitidas
  api/package-info.java     interfaz nombrada "api"
  internal/package-info.java implementación reservada
```

`api` se expone con `@NamedInterface("api")` para contratos entre módulos. Es distinto de un endpoint HTTP. No se han creado servicios, métodos, DTO ni entidades ficticios: los contratos concretos se incorporarán cuando un caso de uso los necesite.

El paquete raíz del módulo solo contiene metadatos. Modulith considera públicos sus tipos por defecto; una regla ArchUnit evita colocar allí clases ejecutables que eludan la separación `api/internal`. Los tipos de implementación, aunque sean Java `public`, siguen siendo internos para otros módulos.

Las capas `internal.web`, `internal.application`, `internal.domain` e `internal.persistence` se crearán con funcionalidad real, sin clases vacías anticipadas. Repositorios y entidades permanecen en su módulo; otro módulo consume contratos públicos e identificadores, conservando las FK y transacciones del modelo cuando correspondan.

## Dependencias iniciales

- Los once módulos distintos de `gestion` declaran `allowedDependencies = {}`: no necesitan todavía consumir otros módulos. Una HU posterior habilita las dependencias concretas que use y las explica en su PR.
- `gestion` puede consumir únicamente las interfaces nombradas `api` de esos once módulos. No puede importar sus internos ni sus paquetes raíz como alternativa al contrato.
- No hay dependencias actuales entre tipos de negocio porque esos tipos aún no existen. La lista de destinos permitidos para `gestion` prepara su función de coordinador; no crea orquestaciones ni le concede acceso empresarial o de base de datos.
- Los módulos operativos no dependen de `gestion`. Por ejemplo, la creación de una acción desde un riesgo se coordinará desde `gestion`, evitando que riesgos y acciones dependan mutuamente de sus repositorios.
- `reportes` incorporará dependencias sobre proyecciones públicas cuando se implementen sus consultas. No se le concede acceso universal a repositorios o esquemas.

Este es el punto inicial de la lista de dependencias, no una prohibición de añadir contratos legítimos en futuras HU. Cada nueva dependencia debe dirigirse a `<modulo>::api`, conservar un grafo sin ciclos y superar las verificaciones.

## Verificación automatizada

`ModularityTests` ejecuta seis comprobaciones:

1. El modelo de producción contiene exactamente los doce módulos aprobados, todos cerrados y con interfaz `api`; `ApplicationModules.verify()` valida ciclos, encapsulación y dependencias permitidas.
2. Las clases de producción, salvo el arranque, pertenecen a `api` o `internal`, sin clases ejecutables en las raíces de los módulos.
3. Un modelo de prueba con dependencia declarada hacia una API válida es aceptado.
4. Un modelo de prueba con ciclo es rechazado.
5. Un modelo de prueba que accede a un tipo interno ajeno es rechazado.
6. Un modelo de prueba que consume una API sin declarar la dependencia es rechazado.

Los cuatro modelos de prueba están en `src/test/java/architecture/fixtures`, fuera de `software.mspi`. Solo sus comprobaciones incluyen explícitamente clases de test; no se incorporan al modelo de producción, al escaneo Spring ni al JAR ejecutable. Las pruebas de rechazo exigen una violación de arquitectura; un error de importación no cuenta como resultado válido.

Desde `mspi/`, con JDK 17, comprobación rápida sin iniciar PostgreSQL:

```powershell
.\mvnw.cmd --batch-mode --no-transfer-progress '-Dtest=ModularityTests' test
```

La validación completa mantiene el requisito de Docker y PostgreSQL de HU-001:

```powershell
.\mvnw.cmd --batch-mode --no-transfer-progress clean verify
```

El workflow existente ejecuta esa validación en los PR hacia `Develop`; no hace falta una etapa o comando opcional para activar los controles modulares. La comprobación rápida no sustituye `clean verify` para cerrar la HU.

Referencias oficiales: [fundamentos de Spring Modulith](https://docs.spring.io/spring-modulith/reference/fundamentals.html) y [verificación de estructura](https://docs.spring.io/spring-modulith/reference/verification.html).

## Límites de esta entrega

No implementa aislamiento PostgreSQL, aprovisionamiento, migraciones empresariales, sesión, permisos HTTP, API de negocio ni coordinación transaccional entre capacidades. Esas verificaciones siguen en sus HU. Encapsular un paquete no concede permisos ni sustituye la autorización empresarial.
