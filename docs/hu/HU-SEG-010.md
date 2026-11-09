# HU-SEG-010. Registro y detección de incidentes de seguridad

El canal operativo `software.mspi.security.events` registra credenciales inválidas, solicitudes sin sesión, accesos denegados, fallos de CSRF, intentos limitados, agotamiento de cuotas o capacidad, cargas demasiado grandes, detecciones de ClamAV y respuestas de inspección no disponible.

Las muestras contienen una categoría fija y el identificador de correlación generado por el servidor. El canal no recibe la URL, la IP, el cuerpo, el correo, las cabeceras, la contraseña, la firma detectada ni los bytes de una evidencia. El historial empresarial sigue documentando los cambios de estado de las evidencias con sus permisos propios.

Por minuto UTC y por categoría, se emiten hasta tres muestras. El décimo evento genera una alerta `WARN` de frecuencia. La primera detección antimalware o respuesta de inspección no disponible genera una alerta inmediata. Al recibir el primer evento de esa categoría en una ventana posterior se resume la ventana anterior si se omitieron muestras. El resumen conserva el total y cuántas muestras se omitieron. Los contadores viven en memoria por instancia.

En Docker, consultar el canal desde una terminal de operador. La API no publica el registro. El despliegue de recursos conserva el controlador `json-file` con rotación de 10 MB y hasta tres archivos por contenedor. El límite corresponde al registro completo del contenedor. Solo los operadores con acceso a Docker deben poder consultar esos registros.

```powershell
docker compose -p mspi-lab logs --no-color --since 10m backend | Select-String 'software.mspi.security.events|seguridad clase='
```

Usar el nombre y los archivos Compose de la instalación. Buscar el valor de `X-Correlation-Id` en el registro para relacionar una muestra o una alerta con la respuesta. Las alertas se consultan localmente en Docker.

Trazabilidad SEC-027, H-09 y P27. La comprobación genera un inicio fallido, una denegación, CSRF inválido, una ráfaga y una detección con una muestra inocua. Se revisa que el identificador coincida, que los registros excluyan los datos introducidos y que Docker tenga la rotación configurada.
