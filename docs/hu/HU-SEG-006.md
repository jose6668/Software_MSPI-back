# HU-SEG-006 — HTTPS para equipo local, LAN y origen de túnel

Rama: feat/HU-SEG-006-https-local-red, base Develop c6c825ce7539825e1fb51b67ae559b73f3d43904.

Como responsable de seguridad quiero un punto de entrada HTTPS verificable, con claves externas a Git y perfiles explícitos de escucha, sin necesitar todavía el frontend.

Se implementan perfiles https-local/https-lan/https-internet, TLS 1.2/1.3, un solo conector TLS, validación de arranque contra degradación de TLS/cookies, escucha y certificado, generador privado de CA/servidor, lanzador sin contraseñas en argumentos y gestión de confianza limitada al usuario actual de Windows. La configuración base de desarrollo queda ligada a loopback.

El helper de túnel prepara un enlace temporal con certificado del origen validado, sin crear un enlace público automáticamente. Las cabeceras de intermediarios permanecen deshabilitadas. Se conserva CSRF, sesión, autorización y los límites de HU-SEG-005.

Criterios: HTTPS real con ambos protocolos modernos; cliente que no confía en la CA o usa un nombre erróneo rechazado; cookies Secure/HttpOnly/Lax e HSTS; ningún segundo conector HTTP; configuración insegura impide arrancar; perfil LAN exige IPv4 privada y SAN correspondiente; certificados y passwords fuera de Git y permisos privados; no se publican bases ni administración mediante la receta.

Pruebas: HttpsDeploymentTests, HttpsLocalIntegrationTests, HttpsLanIntegrationTests, HttpsInternetIntegrationTests y generador ejecutado en CI, además de regresión completa y comprobaciones de secretos. Los resultados y enlaces de evidencia se incorporan al PR de la rama.

[Operación, puesta en marcha y límites pendientes](../HTTPS-Y-EXPOSICION.md). Alcance parcial H-06/SEC-023/024: no se acredita firewall ni acceso público/LAN desde otro equipo hasta realizar esa verificación real. No se modifican esquema común V13, empresarial V7 ni roles.

## Evidencia

PR hacia Develop: [#24](https://github.com/jose6668/Software_MSPI-back/pull/24).
Commit de implementación inicial: b4a354ffaffc60c1a946268ac2cf0fe81795c111.

[Backend CI](https://github.com/jose6668/Software_MSPI-back/actions/runs/42530304809): **173 pruebas, 0 fallos, 0 errores y 0 omitidas; BUILD SUCCESS**, Java 17 y PostgreSQL desechable. Incluye 30 pruebas nuevas de HTTPS y validación de despliegue. [Secret hygiene](https://github.com/jose6668/Software_MSPI-back/actions/runs/42530304858): correcto. El generador se ejecutó con JDK 17 en CI y validó ubicación y permisos privados.

En Windows se generaron certificados fuera de Git, se verificó una ACL sin herencia con una sola regla del usuario actual, y el usuario confirmó el registro de la CA en CurrentUser/Root. Una comprobación nueva de la cadena del certificado resultó válida. El modo ShowCommand del helper de túnel se verificó sin publicar Internet. No se declara que el backend esté ejecutándose en el equipo local: aún faltan JDK 17 y Docker/PostgreSQL operativos para ese arranque.

El commit posterior refuerza el rechazo de enlaces/junctions en el destino del generador. Sus comprobaciones propias y las de commits posteriores quedan en el PR. No se acredita todavía acceso externo desde LAN o Internet.
