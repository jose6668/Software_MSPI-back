# Inspección y cuarentena de evidencias

HU-SEG-008 añade ClamAV al flujo privado de evidencias de evaluación. Conserva PDF, PNG y JPEG, el máximo de 10 000 000 bytes, la cuota por empresa, la verificación de formato y SHA-256, el control de revisión y la descarga como adjunto sin caché.

1. La carga verifica autorización, respuesta, revisión y tamaño. El archivo queda en el espacio temporal privado de la solicitud mientras se inspecciona. Solo se procesa su formato y se publica después de un veredicto limpio. La transacción de publicación vuelve a comprobar los permisos y la revisión. Un rechazo del motor no consume cuota ni modifica el borrador.
2. La descarga comprueba tamaño y huella, inspecciona los mismos bytes que devolverá y comprueba de nuevo los permisos. Una detección sobre un archivo existente registra `BLOQUEADA` y un evento de auditoría. Esta cuarentena es lógica: el contenido se conserva en el volumen privado y se excluye del listado, la descarga y el uso como evidencia de cierre. No se libera automáticamente aunque se actualicen las firmas. La cuota sigue contabilizando el espacio ocupado.
3. Las evidencias anteriores a V8 quedan en `PENDIENTE`; se pueden listar sus metadatos y se inspeccionan al solicitar el archivo. Si el motor falla, el archivo no se entrega. Una descarga posterior puede resolver `ERROR` con una inspección limpia. Los estados de inspección no modifican las revisiones ni las instantáneas de evaluaciones.

La API devuelve `422 EVIDENCIA_BLOQUEADA` ante una detección o alerta del motor y `503 INSPECCION_EVIDENCIAS_NO_DISPONIBLE` si no obtiene un veredicto verificable. Los clientes pueden esperar el `Retry-After` y permitir reintentar el 503; una evidencia ya bloqueada responde 404. No se exponen firmas detectadas, rutas ni datos del archivo en los problemas de la API. El registro del motor contiene solo la versión y el identificador del catálogo.

La inspección permite dos operaciones a la vez y espera como máximo tres segundos por veredicto, incluida la consulta de versión. Las firmas oficiales deben tener una antigüedad máxima de 48 horas. En carga y descarga se consulta el motor fuera de la transacción empresarial. En la verificación de una evidencia para revisión o cierre, cada archivo vuelve a inspeccionarse bajo el límite de transacción de HU-SEG-007. Un error impide la transición; una detección invalida la evidencia. Si esa detección ocurre dentro de la transacción de cierre, el rechazo se devuelve al editor y la marca de cuarentena se registra al detectar el archivo en una descarga autorizada.

## Instalación

1. Actualizar cada empresa de V7 a V8 mediante [el procedimiento `upgrade`](RECUPERACION-PREPARACION.md#actualizar-una-empresa-existente-a-la-versión-vigente). Se mantiene V13 común. La actualización aplica aunque el módulo de evidencias esté deshabilitado. Coordinar la suspensión, respaldo y reactivación de las empresas antes de desplegar.
2. Arrancar ClamAV con [la configuración de límites](../deploy/antimalware/clamd.conf) y FreshClam con firmas oficiales. Para despliegue nativo, usar `TCPAddr 127.0.0.1`, adaptar las rutas y configurar el proceso en UTC. En Windows, retirar las opciones del socket Unix y actualizar el catálogo en el directorio privado que usa clamd. Mantener el puerto 3310 fuera del firewall y del túnel; el protocolo no autentica ni cifra.
3. Mantener la configuración y las credenciales en archivos privados fuera de Git. Con la [configuración de almacenamiento de HU-025](hu/HU-025.md), añadir lo siguiente a la configuración externa del backend. Habilitar el módulo requiere un host para el motor.

```yaml
mspi:
  evidence:
    antimalware:
      host: 127.0.0.1
      port: 3310
      timeout-millis: 3000
      signature-max-age-hours: 48
```

Con Docker, usar el complemento sobre el despliegue acotado de HU-SEG-007. `MSPI_EVIDENCE_VOLUME` debe apuntar al volumen privado existente, accesible por el UID 10001 del backend. Configurar la habilitación del módulo, las cuotas y la tenencia mediante la configuración privada de esa instalación.

```powershell
docker compose -f deploy/resources/compose.yaml -f deploy/antimalware/compose.yaml up -d
```

El complemento fija la imagen ClamAV 1.4.6 LTS por digest, ejecuta el motor sin privilegios y limita CPU, memoria, procesos y temporales. El backend se comunica con el motor por una red privada. FreshClam necesita salida hacia la distribución oficial para renovar firmas; solo el backend publica HTTPS. Sin Internet se pueden importar firmas oficiales vigentes mediante un proceso de actualización fuera de línea. Un catálogo vencido impide servir archivos.

Para la demostración en el mismo equipo, LAN o túnel, probar un archivo limpio, una detección con el entorno de prueba y un intento con ClamAV detenido. No usar malware real. Tras una alerta, revisar el identificador correlacionado en auditoría y aplicar el procedimiento de incidentes. Si se retira contenido del volumen, hacerlo como operador con respaldo y conciliación de la cuota; la API no tiene una acción de liberación o borrado.

El veredicto del motor reduce la exposición a amenazas conocidas y heurísticas. La reconstrucción de documentos (CDR), la inspección de archivos fuera de estos endpoints y el control de una cuenta con acceso directo al sistema operativo quedan fuera de esta historia.

Referencias: [protocolo clamd](https://docs.clamav.net/manual/Usage/ClamdProtocol.html), [firmas oficiales](https://docs.clamav.net/manual/Usage/SignatureManagement.html), [OWASP File Upload Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/File_Upload_Cheat_Sheet.html).
