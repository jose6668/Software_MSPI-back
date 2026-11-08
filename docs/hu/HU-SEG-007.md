# HU-SEG-007 — Presupuestos SQL y de ejecución

Rama feat/HU-SEG-007-recursos-sql. Base Develop: a0985f205a4798b7c3890ec25d8e7afa4411c545.

Como responsable de seguridad quiero que una consulta o cliente que exceda su presupuesto no mantenga ocupados indefinidamente los recursos de la aplicación y que el servicio pueda recuperar capacidad tras el rechazo.

Se mantienen y acotan los pools existentes, aplicando una política de sesión al pool común y después de RESET ALL en los empresariales. Incluye plazos de sentencia, bloqueo, transacción inactiva y duración total de transacción, work_mem y ausencia de paralelismo por consulta. El operador fija temp_file_limit y límites de conexiones en roles runtime; el backend verifica ese tope y no otorga permisos para desactivarlo. Los errores esperados son 503 BASE_OCUPADA sin SQL ni credenciales.

El perfil Docker de evaluación impone CPU, memoria, procesos/hilos, raíz protegida y tmpfs acotado con HTTPS y secretos por archivos. No sustituye los límites de entrada de HU-SEG-005 ni los perfiles nativos de HU-SEG-006. El arranque nativo solo acota JVM; las cotas del sistema requieren el contenedor. [Operación y despliegue](../RECURSOS-Y-SQL.md).

Pruebas: SqlResourceIntegrationTests, SqlBudgetErrorTests, regresión completa y scripts/resources/test_container_budget.py en un proyecto Docker desechable. La prueba de contenedor comprueba TLS, límites efectivos, raíz de solo lectura, ausencia de puertos de PostgreSQL y recuperación tras agotar su temporal. Los resultados y enlaces de evidencia se registran en el PR.

Instalaciones existentes: aplicar db/provisioning/runtime-budgets.sql mediante el operador y reconectar pools antes de usar esta revisión. No se modifican datos ni versiones de Flyway: común V13 y empresarial V7. No se aplicó el script a una base local del usuario.

Trazabilidad: parte pendiente de H-02/SEC-021/P21, con relación a SEC-012/024. No se declara cerrada toda la disponibilidad ni H-03. Persisten medición de capacidad del equipo, cuotas del volumen persistente, antimalware y recuperación integral. Docker continúa sin estar disponible localmente; la validación completa corresponde al CI.

## Evidencia

PR hacia Develop: [#25](https://github.com/jose6668/Software_MSPI-back/pull/25).
Commit de implementación verificado: e28cb8869d64d6e22f2838681ba4e6fe7553cf89.

[Backend CI](https://github.com/jose6668/Software_MSPI-back/actions/runs/42534421354): **189 pruebas, 0 fallos, 0 errores y 0 omitidas; BUILD SUCCESS**, Java 17 y PostgreSQL 17.11. Incluye 8 pruebas nuevas de recursos SQL y 8 de errores públicos. La prueba adicional de despliegue construyó y arrancó los servicios reales y terminó con: HTTPS validado, CPU/RAM/PID acotados, raíz protegida, base sin puertos y tmpfs agotado/recuperado. [Secret hygiene](https://github.com/jose6668/Software_MSPI-back/actions/runs/42534421393): correcto.

El arnés espera la disponibilidad TCP definitiva de PostgreSQL, no su servidor transitorio de inicialización. Los secretos de despliegue se copian a archivos privados del UID de servicio y se excluyen también por nombre en los controles de Git. La evidencia corresponde al commit indicado; los commits posteriores conservan sus comprobaciones propias. El refinamiento posterior comprueba además el error de espacio y tamaño del archivo de prueba al agotar tmpfs.

No se ejecutaron ataques ni cambios de roles sobre una instalación del usuario; el despliegue y sus datos de prueba fueron desechables y quedaron eliminados por el arnés.
