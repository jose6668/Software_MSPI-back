# Respaldo y restauración en Docker

El inventario admite empresas V8 y V9. En V9 coteja también los recuentos de implementaciones, versiones, confirmaciones de aplicación y verificaciones. Las evidencias de control se cotejan por tamaño y SHA-256 en el mismo volumen privado que las evidencias de evaluación.

La herramienta `scripts/recovery/backup_restore.py` requiere Python 3.12 o posterior y acceso de operador a Docker. Trabaja con el despliegue de recursos y la extensión de evidencias, PostgreSQL de la imagen fijada en Compose y los esquemas empresariales V8. El destino usa la misma variante de la imagen y un volumen nuevo. Esto es una copia física del clúster completo.

## Crear una copia

Coordinar una ventana de mantenimiento. El proyecto debe usar un volumen PostgreSQL dedicado sin puerto publicado y un directorio privado de evidencias montado en el backend. Reservar acceso exclusivo al entorno y detener cualquier escritor externo durante la operación. La herramienta detiene el backend, verifica los metadatos de evidencia, apaga PostgreSQL y comprueba que terminó limpiamente. Al concluir o fallar, intenta restablecer los servicios que encontró activos.

```powershell
python scripts/recovery/backup_restore.py backup --project mspi-lab --bundle C:/RutaPrivada/mspi-copia
```

El directorio debe ser nuevo y estar fuera de Git. Contiene `postgres.tar`, las evidencias y `manifest.json` con las huellas y el inventario. La existencia del manifiesto indica que se completó la captura. Si falla la conciliación, revisar los archivos huérfanos o faltantes antes de repetir con otro directorio.

El archivo de base contiene datos empresariales, hashes de credenciales y roles. El script crea una carpeta privada y comprueba integridad mediante SHA-256. Guardar la copia en almacenamiento cifrado con acceso de operador. Conservar el manifiesto por un canal confiable junto con la copia. Los hashes por sí solos permiten detectar daños, pero un tercero que altere tanto el archivo como el manifiesto podría cambiar el resultado. La copia excluye los archivos de credenciales, las claves TLS y el catálogo de ClamAV. Custodiar esos materiales por separado.

## Recuperar en un proyecto nuevo

Usar un proyecto con prefijo `mspi-restore-` y un directorio de evidencias inexistente. Tener disponible la imagen de PostgreSQL del despliegue antes de restaurar. El comando verifica la copia y crea los datos del destino sin iniciarlo.

```powershell
python scripts/recovery/backup_restore.py restore --project mspi-restore-ensayo --bundle C:/RutaPrivada/mspi-copia --evidence C:/RutaPrivada/mspi-evidencias-restauradas
```

En Linux, asignar al UID 10001 acceso de lectura y escritura sobre el directorio de evidencias restauradas. Restringir el acceso a la identidad del servicio y al operador de respaldo. En Windows, conservar la ACL privada del directorio que creó la herramienta.

Preparar los archivos privados del destino con el TLS, las credenciales vigentes al momento de la copia y la configuración de las empresas y sus cuotas. Los verificadores de PostgreSQL recuperados deben corresponder a esos secretos. Mantener el nombre de servicio `database` como host JDBC en la red del proyecto. Usar un puerto local libre y apuntar al directorio recién restaurado. Si la instalación usa un Compose privado para montar la configuración de tenencia, añadir ese archivo a la lista de `-f`.

```powershell
$env:MSPI_EVIDENCE_VOLUME = 'C:/RutaPrivada/mspi-evidencias-restauradas'
$env:MSPI_HTTPS_PORT = '9443'
docker compose -p mspi-restore-ensayo -f deploy/resources/compose.yaml -f deploy/antimalware/compose.yaml up -d --wait
python scripts/recovery/backup_restore.py verify --project mspi-restore-ensayo --bundle C:/RutaPrivada/mspi-copia
```

El destino mantiene PostgreSQL y ClamAV sin puertos publicados. Dejar que ClamAV actualice su catálogo antes de probar descargas. Ejecutar `verify` antes de permitir escrituras en el destino. Después, probar el inicio de sesión, el acceso denegado a otra empresa, una descarga íntegra y el bloqueo de un archivo en cuarentena. Medir desde el inicio de la restauración hasta que estos controles estén operativos.

Si la restauración falla tras crear recursos, conservarlos para diagnóstico. Repetir con un nombre y directorio nuevos una vez corregida la causa. La herramienta rechaza destinos Docker existentes. La restauración selectiva y los objetivos de pérdida de datos o tiempo de recuperación quedan fuera de este procedimiento.
