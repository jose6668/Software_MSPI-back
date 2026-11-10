/**
 * Proyecciones autorizadas y reporte básico.
 */
@org.springframework.modulith.ApplicationModule(
		displayName = "Reportes",
		allowedDependencies = {"acciones::api", "empresas::api", "evaluaciones::api", "riesgos::api"})
package software.mspi.reportes;
