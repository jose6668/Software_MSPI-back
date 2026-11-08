# HTTPS y exposición del backend — HU-SEG-006

El backend sirve HTTPS directamente con el Tomcat integrado. No requiere frontend ni proxy local adicional. Dentro del proceso HTTPS solo existe un conector TLS; no se crea un puerto HTTP alternativo para saltar los controles. La aplicación de desarrollo sin perfil HTTPS también cambia su escucha predeterminada a loopback; usar un perfil HTTPS antes de ofrecer acceso a otros equipos.

## Perfiles

| Perfil | Escucha | Uso |
| --- | --- | --- |
| https-local | 127.0.0.1:8443 | Navegador/API del mismo equipo |
| https-lan | IPv4 privada indicada en MSPI_HTTPS_LAN_ADDRESS:8443 | Otro equipo en la red local |
| https-internet | 127.0.0.1:8443 | Origen privado de un túnel con HTTPS y validación de certificado |

Todos importan https-common.yaml: PKCS12 externo, alias mspi-server, TLS 1.2/1.3, cookies Secure/HttpOnly/Lax y forward-headers-strategy=none. La validación de arranque rechaza perfiles HTTPS desconocidos o simultáneos, desactivación de TLS o cookies protegidas, protocolos antiguos, un bundle SSL que cambie la receta, escuchas comodín/públicas y un puerto de administración separado. Comprueba vigencia, uso de servidor y SAN de la IP del certificado. No se guardan claves en YAML ni dentro del JAR.

Esta configuración no modifica PostgreSQL ni el firewall del sistema por sí sola. No garantiza que otro proceso del equipo no esté escuchando en 8080/5432. Conservar PostgreSQL en loopback y, si se usa Docker, publicar la base solo como 127.0.0.1:5432:5432. No publicar Docker, puertos de gestión ni PostgreSQL mediante el túnel. El profesor que entra desde la LAN/Internet debe acceder únicamente al endpoint HTTPS; el administrador del sistema sigue siendo un límite de confianza.

## Preparar certificados fuera de Git

Desde la raíz del repositorio, en PowerShell con un JDK instalado:

```powershell
$tlsDirectory = Join-Path $env:LOCALAPPDATA 'MSPI/https/evaluacion-01'
./scripts/https/New-MspiTlsMaterial.ps1 -OutputDirectory $tlsDirectory -LanAddress '192.168.50.10'
```

La IP es un ejemplo: indicar la IPv4 privada real que usará el equipo. El generador rechaza IP públicas y directorios dentro de cualquier repositorio Git, y no sobrescribe un destino existente. También rechaza enlaces simbólicos y junctions en la ruta de destino. Crea una CA privada local de un año y un certificado de servidor de 90 días, RSA 3072 y SHA-256, con SAN localhost/127.0.0.1 y la IP LAN si se proporciona. Puede omitirse LanAddress para uso exclusivamente local.

El directorio de Windows tiene herencia desactivada y acceso solo para el usuario que lo genera; en Linux usa permisos 700. Dentro quedan server.p12, server-password.txt, ca.p12, ca-password.txt y los certificados públicos ca.crt/server.crt. Los passwords son aleatorios, distintos y se pasan a keytool por variables de entorno temporales, nunca como valores en argumentos. No se imprimen. Las exclusiones y el control de secretos también prohíben versionar los dos archivos de contraseña, incluso forzando su incorporación a Git.

El almacén de CA y su contraseña no son necesarios para ejecutar la aplicación. Mantenerlos apartados del usuario de evaluación; compartir únicamente ca.crt. La CA permite emitir certificados: no compartir sus archivos privados. La protección por ACL no impide la lectura por el mismo usuario o un administrador con privilegios.

Para que Windows confíe en esta CA, revisar primero su procedencia y registrar únicamente el certificado público en el usuario actual:

```powershell
./scripts/https/Trust-MspiLocalCa.ps1 -TlsDirectory $tlsDirectory
```

Windows puede solicitar una confirmación con su ventana de advertencia de seguridad. Comprobar que corresponde a esta CA local antes de aceptarla. El comando imprime la identidad pública registrada. No modifica la confianza de todos los usuarios ni exporta claves. Para retirarla al terminar la evaluación:

```powershell
./scripts/https/Trust-MspiLocalCa.ps1 -TlsDirectory $tlsDirectory -Remove
```

En cada equipo de la LAN hay que confiar también en el certificado público de esa CA, mediante un canal de entrega confiable. El almacén de confianza de algunos navegadores puede ser independiente. No saltar advertencias de certificado ni usar opciones que desactiven su verificación. Un cambio de IP exige un certificado nuevo con ese SAN; regenerar material en otro directorio también crea otra CA, que requiere gestionar de nuevo la confianza. No hay renovación automática: renovar antes de 90 días y retirar CA antiguas que ya no se utilicen.

## Arranque

Construir el backend con JDK 17 y tener PostgreSQL y sus migraciones comunes/empresariales preparadas. Las credenciales de base de datos continúan por configuración externa; la plantilla .env.example no se carga automáticamente. El generador puede ejecutarse con otro JDK que tenga keytool, pero el lanzador exige Java 17 para la aplicación.

```powershell
# Mismo equipo: https://localhost:8443/api/v1
./scripts/https/Start-MspiHttps.ps1 -TlsDirectory $tlsDirectory -Mode local

# Red local: https://192.168.50.10:8443/api/v1
./scripts/https/Start-MspiHttps.ps1 -TlsDirectory $tlsDirectory -Mode lan -LanAddress '192.168.50.10'
```

El lanzador lee la contraseña del almacén privado, la introduce solo en el entorno del proceso y restaura el entorno al finalizar. No añade contraseñas a la línea de comandos. Por debajo activa exactamente un perfil HTTPS. El backend aún responde como API: sin frontend no aparecerá una pantalla de inicio de sesión.

Para LAN, revisar el firewall y habilitar únicamente el puerto HTTPS hacia la subred privada autorizada. No habilitarlo en redes públicas ni abrir puertos del router. Usar Test-MspiListeningPorts.ps1 como inventario de solo lectura de 8443, 8080 y 5432; identifica bases que escuchan fuera de loopback y otros procesos en 8080. Revisar también desde otro equipo. No cambiar listeners de otros programas sin identificar sus propietarios.

## Enlace temporal de Internet, opcional

Un Quick Tunnel de Cloudflare entrega un hostname temporal sin necesitar dominio; termina al cerrar el proceso y su nombre cambia al crearlo de nuevo. Es para pruebas y no tiene garantía de disponibilidad. Se preparó la receta para cloudflared 2026.10.7, verificada contra la descarga oficial. No se instala automáticamente ni se publica una URL al generar certificados o iniciar HTTPS.

```powershell
# Terminal 1: solo escucha local, cifrada.
./scripts/https/Start-MspiHttps.ps1 -TlsDirectory $tlsDirectory -Mode internet

# Terminal 2: revisar primero el comando; esto NO publica Internet.
./scripts/https/Start-MspiQuickTunnel.ps1 -TlsDirectory $tlsDirectory -ShowCommand

# Al ejecutar sin ShowCommand se publica el enlace; cerrar con Ctrl+C.
./scripts/https/Start-MspiQuickTunnel.ps1 -TlsDirectory $tlsDirectory
```

El helper rechaza variables TUNNEL_* o NO_TLS_VERIFY y configuraciones persistentes de túnel para impedir que otra receta sobrescriba el origen previsto. No cambia esas configuraciones. Exige un origen HTTPS funcionando con certificado válido antes de publicar. El túnel conecta a https://127.0.0.1:8443, fija origin-server-name=localhost y origin-ca-pool al ca.crt privado de esta instalación, y mantiene no-tls-verify=false. Los tiempos de conexión y negociación TLS al origen son de 5 segundos. No se incluye un origen HTTP alternativo. Verificar el nombre y certificado públicos desde un equipo externo antes de compartirlo. El visitante del enlace público no necesita instalar la CA local: su navegador valida el certificado público del proveedor; ca.crt se utiliza en el conector hacia el origen.

Cloudflare termina el TLS público y crea otra conexión TLS hacia el origen; es un intermediario que puede procesar el tráfico. El cifrado de ambos tramos no elimina esa confianza. La URL temporal no sustituye autenticación ni permisos del backend. No se ha publicado un enlace real como parte de la implementación ni se acredita una prueba de tráfico por Internet.

El backend ignora Forwarded/X-Forwarded-* y conserva la dirección remota del contenedor. En un túnel todos los visitantes pueden compartir la IP loopback y la misma cuota por dirección, incluidos los límites de login. No confiar ciegamente en cabeceras de IP para separar visitantes. La identidad de cliente del intermediario y el bloqueo inducido H-03 siguen pendientes; esta entrega no los declara resueltos.

## Pruebas y alcance

CI ejecuta el generador con JDK 17 en un directorio temporal fuera del checkout y comprueba permisos privados y rechazo de un destino dentro de Git. Los tests usan esa CA y los tres perfiles sobre Tomcat y PostgreSQL desechables: TLS 1.2/1.3, un único conector HTTPS, HTTP sin acceso alternativo, cookies y HSTS, cabeceras de proxy falsas, CA desconocida y nombre de servidor incorrecto. También prueban que una configuración que desactive TLS impida iniciar la aplicación.

Los tests LAN e Internet validan los perfiles en la máquina de CI: no simulan otro computador del usuario ni un túnel público activo. La base de desarrollo local requiere JDK 17 y Docker/PostgreSQL disponibles; no se atribuye esa ejecución completa al equipo local si faltan esos requisitos. Certificados, raíces de confianza y puertos deben verificarse nuevamente en el equipo y la red de evaluación.

Trazabilidad: H-06, SEC-023 y parte de SEC-024/P23/P24. No se declara cerrado H-06 globalmente: quedan firewall, separación de cuentas, protección operativa de PostgreSQL, límites del proceso/SQL, medición de capacidad, exposición externa real y revisión del frontend cuando exista. No hay migraciones de datos ni cambios de permisos empresariales.

Fuentes: [SSL de Spring Boot](https://docs.spring.io/spring-boot/how-to/webserver.html), [keytool](https://docs.oracle.com/en/java/javase/17/docs/specs/man/keytool.html), [Quick Tunnels](https://developers.cloudflare.com/tunnel/get-started/quick-tunnels/), [certificados del origen](https://developers.cloudflare.com/tunnel/troubleshooting/https-origins/).
