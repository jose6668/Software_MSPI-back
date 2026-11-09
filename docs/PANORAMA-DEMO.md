# Datos de demostración Panorama

La muestra reconstruye la empresa ficticia Andina Servicios S.A.S. del boceto. La carga es manual, se ejecuta por HTTPS contra Docker en el mismo equipo y utiliza las operaciones normales del backend. Está separada de las migraciones y puede retomarse si se interrumpe. Ejecutar una sola instancia a la vez.

## Preparación

1. Aprovisionar **Andina Servicios S.A.S.** en un entorno de demostración con su propio esquema y conexión de runtime. Dar a una cuenta de demostración AE o GS y preparar la membresía que será responsable de la evaluación. Usar los procedimientos del [ciclo de empresas](CICLO-EMPRESAS.md) y [migraciones](RECUPERACION-PREPARACION.md).
2. Publicar **F1-1** con el [procedimiento de HU-016](hu/HU-016.md). El generador verifica la huella del Excel original y Flyway prepara la metodología **EVALUACION_CONTROLES_V1**.
3. Para comprobar separación de datos, aprovisionar también **Litoral Logística S.A.S.** con su propio esquema. Puede estar vacía y tener una membresía AU para una cuenta que sea GS en Andina.

## Carga

Desde la raíz del backend, indicar el origen HTTPS directo del backend local, la CA del laboratorio y los UUID de Andina y de la membresía responsable.

~~~powershell
python scripts/demo/seed-panorama.py --origin https://127.0.0.1:8443 --ca C:/RutaPrivada/ca.crt --company UUID_ANDINA --responsible UUID_MEMBRESIA --email laura.panorama@example.test
~~~

El programa pide la contraseña en la consola. Para automatización local, se admite un archivo privado con una sola línea mediante `--password-file`. Las credenciales, el Excel y la configuración de conexiones se guardan fuera de Git.

## Verificación

- El diagnóstico queda en borrador y usa los elementos del catálogo F1-1. La API concilia 93 controles, de los cuales 60 están evaluados, 20 sin calificar, 5 sin decidir y 8 no aplican. El resultado es 64,00 puntos provisionales y 70,59 % de avance. Las cláusulas se consultan por separado.
- El inventario contiene 48 activos ficticios, con 39 clasificaciones confirmadas y 9 pendientes. Los casos pendientes conservan integridad sin diligenciar.
- Litoral sirve como contraste vacío. Riesgos y acciones quedan para sus historias del backend.
- Se puede volver a ejecutar la carga sobre la misma muestra. Los registros coincidentes se reutilizan. Si una respuesta o registro existente difiere, la carga se detiene en ese punto para evitar sobrescribirlo.
