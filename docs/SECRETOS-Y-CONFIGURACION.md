# Secretos y configuración privada

HU-SEG-002 implementa prevención de publicación accidental de secretos. No cambia endpoints, sesiones, persistencia ni migraciones y permite continuar otras historias en ramas desde Develop.

## Configuración de desarrollo

La plantilla raíz `.env.example` es pública y contiene únicamente nombres, valores no sensibles y secretos vacíos. **Spring Boot no carga automáticamente archivos .env**. Usar variables de entorno del proceso o el mecanismo de configuración externa del entorno. Una copia local no versionada puede servir como referencia, pero no debe incorporarse a Git ni imprimirse en terminales, capturas o logs.

Para el backend, proporcionar fuera de Git las credenciales ordinarias mediante `SPRING_DATASOURCE_USERNAME` y `SPRING_DATASOURCE_PASSWORD`. Las credenciales de migración, conexiones empresariales y SMTP también se suministran externamente conforme a sus guías existentes. No introducirlas en la URL JDBC. La plantilla no habilita funciones opcionales sin completar su configuración.

Guardar las credenciales fuera de carpetas compartidas y limitar lectura a la identidad del servicio. Una persona que controla la misma cuenta del sistema operativo puede acceder a sus archivos y procesos: .gitignore no proporciona cifrado ni control de permisos.

## Controles implementados

- `.gitignore` raíz protege archivos .env y configuración privada anidada, claves/almacenes, volcados y directorios de secretos. `.env.example` continúa versionable.
- `check_secret_hygiene.py` falla si alguien fuerza con `git add -f` un archivo sensible; comprueba exclusiones, plantillas y credenciales dentro de URLs. No imprime valores.
- `Secret hygiene` ejecuta estas comprobaciones y Gitleaks 8.30.1 en PR hacia Develop y pushes a Develop. Descarga el binario oficial con versión y SHA-256 fijados, consulta el historial completo disponible y los archivos actuales, con redacción del 100 %.
- El workflow tiene permiso `contents: read`, checkout sin credenciales persistidas y no consume secretos del repositorio. No usa `pull_request_target`.
- Las pruebas negativas generan un comprobante sintético únicamente en un repositorio temporal: prueban detección actual, detección después de borrarlo del último commit y ausencia del valor en los reportes. No se publica un comprobante de prueba en este historial.

La comprobación devuelve fallo cuando encuentra un problema; para impedir administrativamente fusiones con fallo hay que configurar ese check como obligatorio en la protección de Develop. Esta entrega no cambia permisos ni protecciones de rama. Gitleaks por patrones no detecta necesariamente todos los secretos y no sustituye la revisión del diff.

## Verificación local

Con Python 3 y Gitleaks 8.30.1 verificado disponibles, desde la raíz:

```powershell
python .github/scripts/check_secret_hygiene.py --self-test --gitleaks 'C:\ruta\gitleaks.exe'
gitleaks git . --log-opts='--all --full-history' --redact=100 --ignore-gitleaks-allow
gitleaks dir . --redact=100 --ignore-gitleaks-allow
```

No añadir `.gitleaksignore` o `.gitleaks.toml` para ocultar un fallo. Una excepción legítima requiere revisar y modificar explícitamente la política en su PR. No usar secretos reales para probar el detector.

## Si una credencial se publica

1. Revocarla o reemplazarla en el servicio y actualizar de forma privada la configuración de quienes la necesitan.
2. Retirarla del código, artefactos y logs sin copiarla a comentarios o incidencias.
3. Evaluar el historial y coordinar su limpieza si corresponde. Borrar un archivo actual no revoca la credencial ni elimina commits anteriores; no reescribir ramas compartidas sin coordinación.
4. Repetir la verificación y conservar evidencia redactada. Incluir claves y copias afectadas en el alcance de la rotación.
