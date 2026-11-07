# Contrato REST de MSPI v1

El contrato OpenAPI 3.1 se versiona junto al código y se incluye en el JAR como recurso. Las consultas públicas `GET /api/v1` y `HEAD /api/v1` informan la versión del contrato. El GET devuelve `{"version":"v1"}`. HU-007 añade el contrato de identidad y CSRF. [OpenAPI de MSPI](../mspi/src/main/resources/openapi/v1.yaml).

Mantener el archivo OpenAPI en el mismo PR que agregue o cambie una operación. Documentar entrada y salida, campos obligatorios y anulables, formatos, estados, unidades, permisos y errores aplicables. La especificación se mantiene de forma explícita con OpenAPI 3.1 y se contrasta con las respuestas HTTP en CI. [Especificación OpenAPI 3.1](https://spec.openapis.org/oas/v3.1.0).

## Entradas y operaciones empresariales

- Definir DTO de entrada y salida para cada operación. Aplicar Bean Validation y tipos explícitos según el diccionario. Jackson rechaza propiedades desconocidas, claves duplicadas y contenido JSON posterior al objeto. También rechaza convertir fracciones o cadenas a enteros y números o booleanos a texto. Usar los campos de entrada permitidos para cada comando.
- Resolver `empresaId` en la ruta empresarial `/api/v1/empresas/{empresaId}/...`. El servidor valida identidad, membresía, permiso y pertenencia antes de acceder al esquema. Autor, empresa efectiva, estado derivado y permisos se resuelven en el servidor.
- Documentar paginación, filtros y ordenaciones permitidos en las operaciones que listen datos. Los tamaños concretos se definen con cada recurso.
- Para una modificación sujeta a revisión, recibir `revisionEsperada` como entero positivo obligatorio. Comparar y actualizar en una transacción con control de concurrencia, por ejemplo con `@Version`. Una revisión obsoleta se traduce a HTTP 409 con `REVISION_DESACTUALIZADA`. La prueba de HU-006 cubre el contrato HTTP con un fixture. La verificación concurrente de las escrituras corresponde a cada historia que las implemente.
- Usar comandos específicos para aprobar, devolver, archivar y cerrar. El contrato de cada comando debe declarar sus datos, precondiciones y resultado. Representar los valores pendientes según su semántica y nulabilidad, conservando la distinción con cero.

## Respuestas de error

Los errores de Spring MVC y los rechazos de autenticación, autorización o CSRF de la cadena API utilizan `application/problem+json`. `code` es el identificador estable que consume el cliente, `detail` es un mensaje legible y `fields` es una lista de objetos con `field`, `code` y `message`. `fields` es una lista vacía si el error carece de campos. Para un cuerpo ilegible o un campo JSON fuera del contrato se indica `body` sin reflejar contenido de la solicitud. [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457.html).

| Estado | Código |
| --- | --- |
| 400 | `VALIDACION`, `JSON_INVALIDO`, `CAMPO_NO_PERMITIDO` o `SOLICITUD_INVALIDA` |
| 401 | `NO_AUTENTICADO` o `CREDENCIALES_INVALIDAS` |
| 403 | `ACCESO_DENEGADO` o `CSRF_INVALIDO` |
| 404 | `NO_ENCONTRADO` |
| 405 | `METODO_NO_PERMITIDO` |
| 406 | `RESPUESTA_NO_ACEPTABLE` |
| 409 | `REVISION_DESACTUALIZADA` o `CONFLICTO` |
| 415 | `TIPO_CONTENIDO_NO_ADMITIDO` |
| 429 | `DEMASIADOS_INTENTOS` |
| Otros 4xx | `SOLICITUD_RECHAZADA` |
| 5xx | `ERROR_INTERNO` |

El servidor genera un UUID por solicitud API, lo devuelve en `X-Correlation-Id` y lo añade como `correlationId` e `instance` a los errores. `instance` es una URN, lo que permite identificar la incidencia sin publicar la URL original. El UUID también está disponible en el contexto de logs durante la solicitud. El cliente puede comunicar ese UUID al reportar un error. Los mensajes están definidos por el backend y la respuesta solo incluye nombres de campos o parámetros conocidos.

El manejo aplica después de que la solicitud llega a la capa HTTP de la aplicación. Un proxy o el contenedor pueden rechazar solicitudes antes de ese punto. Spring Security mantiene CSRF. Las consultas de empresas propias y de contexto exigen sesión válida y autorización vigente. Fuera de la API se conservan las comprobaciones de salud. [Flujo de sesión](SESION.md) y [permisos por empresa](PERMISOS-EMPRESA.md).
