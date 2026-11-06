/**
 * Coordinación de casos de uso entre módulos mediante sus API.
 */
@org.springframework.modulith.ApplicationModule(
		displayName = "Gestión",
		allowedDependencies = {
		"identidad::api",
		"empresas::api",
		"catalogos::api",
		"activos::api",
		"evaluaciones::api",
		"controles::api",
		"riesgos::api",
		"acciones::api",
		"evidencias::api",
		"auditoria::api",
		"reportes::api"
})
package software.mspi.gestion;
