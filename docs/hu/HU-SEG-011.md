# HU-SEG-011. Revisión de vulnerabilidades en cada PR

Backend CI exporta el árbol resuelto de Maven y consulta OSV por cada par de paquete y versión. Incluye dependencias transitivas y de pruebas. El análisis de imágenes usa Trivy con la versión y el SHA-256 del ejecutable fijados. Examina los paquetes del sistema operativo del backend recién construido y las imágenes de PostgreSQL y ClamAV fijadas por digest en Compose.

La comprobación marca Backend CI como fallido en estos casos:

- OSV devuelve un aviso para una dependencia resuelta.
- Trivy reporta severidad alta o crítica en una imagen, incluso si el reporte no indica una corrección.
- Falta un reporte, no se pudo consultar la fuente, la respuesta quedó incompleta o Trivy no pudo inventariar el sistema operativo de la imagen solicitada.

El artefacto de GitHub `vulnerability-reports` contiene `summary.json`, el inventario de Maven y los reportes de las imágenes. Se conserva durante siete días. Los hallazgos medios y bajos aparecen en los informes. Un estado `incomplete` requiere corregir el escaneo y repetirlo. Un estado `findings` requiere revisar el aviso y aplicar la corrección o documentar el caso para decisión del equipo. Mantener Backend CI en verde como requisito de revisión antes de fusionar.

La primera ejecución identificó CVE-2026-109518 en `apt` y `libapt-pkg6.0` del backend. El Dockerfile instala la corrección de Ubuntu por HTTPS y comprueba la versión mínima `2.4.15ubuntu0.1`. La imagen final continúa usando el usuario 10001. [Aviso USN-8996-1](https://ubuntu.com/security/notices/USN-8996-1)

Alcance SEC-026 y P26. Se comprueban avisos publicados para las coordenadas Maven consultadas y los paquetes del sistema operativo reconocidos en las imágenes que construye el runner Linux x64. Los binarios instalados fuera del gestor de paquetes, el host Docker, los plugins de compilación y las demás arquitecturas requieren su propia revisión.
