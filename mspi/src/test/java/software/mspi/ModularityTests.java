package software.mspi;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.core.Violations;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModularityTests {

	private static final ApplicationModules MODULES = ApplicationModules.of(MspiApplication.class);
	// Only the fixture models import test classes; production keeps Modulith's default exclusions.
	private static final ImportOption INCLUDE_FIXTURES = location -> true;

	@Test
	void verifiesAllTwelveClosedModulesAndTheirApiBoundaries() {
		assertThat(MODULES.stream().map(module -> module.getIdentifier().toString()))
				.containsExactlyInAnyOrder(
						"identidad", "empresas", "catalogos", "activos", "evaluaciones", "controles",
						"riesgos", "acciones", "evidencias", "auditoria", "reportes", "gestion");

		MODULES.forEach(module -> {
			assertThat(module.isOpen()).as("Módulo cerrado: %s", module.getIdentifier()).isFalse();
			assertThat(module.getNamedInterfaces().getByName("api"))
					.as("API explícita: %s", module.getIdentifier()).isPresent();
		});
		MODULES.verify();
	}

	@Test
	void keepsProductionTypesInsideApiOrInternalPackages() {
		var productionClasses = new ClassFileImporter()
				.withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
				.importPackages("software.mspi");

		// Module roots are public API to Modulith; keep executable types out of them.
		classes().that().haveSimpleNameNotEndingWith("package-info")
				.and().doNotHaveFullyQualifiedName(MspiApplication.class.getName())
				.should().resideInAnyPackage("software.mspi.*.api..", "software.mspi.*.internal..")
				.allowEmptyShould(true)
				.check(productionClasses);
	}

	@Test
	void acceptsAnExplicitDependencyOnANamedApi() {
		ApplicationModules.of("architecture.fixtures.allowed", INCLUDE_FIXTURES).verify();
	}

	@Test
	void rejectsCycles() {
		assertThatThrownBy(() -> ApplicationModules.of("architecture.fixtures.cycle", INCLUDE_FIXTURES).verify())
				.isInstanceOf(Violations.class)
				.hasMessageContaining("Cycle detected");
	}

	@Test
	void rejectsAccessToAnotherModulesInternalTypes() {
		assertThatThrownBy(() -> ApplicationModules.of("architecture.fixtures.internals", INCLUDE_FIXTURES).verify())
				.isInstanceOf(Violations.class)
				.hasMessageContaining("non-exposed type");
	}

	@Test
	void rejectsAnUndeclaredDependencyEvenOnAPublicApi() {
		assertThatThrownBy(() -> ApplicationModules.of("architecture.fixtures.forbidden", INCLUDE_FIXTURES).verify())
				.isInstanceOf(Violations.class)
				.hasMessageContaining("consumer")
				.hasMessageContaining("owner");
	}

}
