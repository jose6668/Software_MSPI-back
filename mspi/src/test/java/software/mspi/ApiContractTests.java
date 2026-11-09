package software.mspi;

import java.util.UUID;
import java.util.stream.Stream;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "management.endpoint.health.probes.enabled=true",
        "management.endpoint.health.probes.add-additional-paths=true"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({PostgresTestConfiguration.class, ApiContractTests.ProbeController.class})
class ApiContractTests {

    private static final String CORRELATION = "X-Correlation-Id";
    private static final String API = "/api/v1";
    private static final String PROBE = API + "/pruebas";
    private static final String VALID_INPUT = """
            {"nombre":"Prueba","revisionEsperada":4}
            """;

    @Autowired MockMvc mvc;
    @Autowired JsonMapper mapper;

    @Test
    void publicVersionAndErrorMatchPublishedOpenApi() throws Exception {
        var resource = new ClassPathResource("openapi/v1.yaml");
        var loaderOptions = new LoaderOptions();
        loaderOptions.setAllowDuplicateKeys(false);
        var yaml = new Yaml(new SafeConstructor(loaderOptions));
        try (var stream = resource.getInputStream()) {
            var spec = mapper.valueToTree(yaml.load(stream));
            var getSpec = spec.path("paths").path(API).path("get").path("responses");
            var result = mvc.perform(get(API).header(CORRELATION, "no-reflejar").accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(header().exists(CORRELATION))
                    .andReturn();
            assertThat(mapper.readTree(result.getResponse().getContentAsByteArray()))
                    .isEqualTo(getSpec.path("200").path("content").path("application/json").path("example"));
            assertThat(UUID.fromString(result.getResponse().getHeader(CORRELATION)).version()).isEqualTo(4);
            assertThat(result.getRequest().getSession(false)).isNull();

            var headResult = mvc.perform(head(API)).andExpect(status().isOk())
                    .andExpect(header().exists(CORRELATION)).andExpect(content().string("")).andReturn();
            assertThat(headResult.getResponse().getHeader(CORRELATION))
                    .isNotEqualTo(result.getResponse().getHeader(CORRELATION));

            var unacceptable = mvc.perform(get(API).accept(MediaType.TEXT_HTML))
                    .andExpect(status().isNotAcceptable()).andReturn();
            assertProblem(unacceptable, "RESPUESTA_NO_ACEPTABLE");
            var body = mapper.readTree(unacceptable.getResponse().getContentAsByteArray());
            var schema = spec.path("components").path("schemas").path("ApiProblem");
            assertThat(body.propertyNames()).containsExactlyInAnyOrderElementsOf(
                    schema.path("required").valueStream().map(node -> node.asString()).toList());
            assertThat(getSpec.path("406").path("$ref").asString()).isEqualTo("#/components/responses/Problem");
        }
    }

    @Test
    void leavesHealthAvailableAndClosesDefaultLoginOutsideTheApi() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(header().doesNotExist(CORRELATION));
        mvc.perform(get("/livez")).andExpect(status().isOk()).andExpect(header().doesNotExist(CORRELATION));
        mvc.perform(get("/login").accept(MediaType.TEXT_HTML))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Location"))
                .andExpect(header().doesNotExist(CORRELATION));
    }

    @Test
    void protectsCompanyRoutesAndReturnsSafeNotFoundForAuthenticatedRequests() throws Exception {
        var company = API + "/empresas/" + UUID.randomUUID();
        var companyPath = company + "/riesgos";
        assertProblem(mvc.perform(get(companyPath)).andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist("Location")).andReturn(), "NO_AUTENTICADO");
        assertProblem(mvc.perform(get(companyPath).with(user("persona"))).andExpect(status().isNotFound())
                .andReturn(), "NO_ENCONTRADO");
        assertProblem(mvc.perform(get(company + "/funcionalidad-no-publicada").with(user("persona"))).andExpect(status().isForbidden())
                .andReturn(), "ACCESO_DENEGADO");
        var notFound = mvc.perform(get(API + "/ruta-privada").with(user("persona")))
                .andExpect(status().isNotFound()).andReturn();
        assertProblem(notFound, "NO_ENCONTRADO");
        assertThat(notFound.getResponse().getContentAsString()).doesNotContain("ruta-privada");
    }

    @Test
    void requiresCsrfAndAcceptsAValidDtoWithAValidToken() throws Exception {
        var denied = mvc.perform(post(PROBE).with(user("persona")).contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_INPUT))
                .andExpect(status().isForbidden()).andReturn();
        assertProblem(denied, "CSRF_INVALIDO");
        mvc.perform(post(PROBE).with(user("persona")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_INPUT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nombre").value("Prueba"))
                .andExpect(jsonPath("$.revision").value(5));
    }

    @Test
    void reportsBeanValidationFieldsWithoutEchoingInputs() throws Exception {
        var result = mvc.perform(post(PROBE).with(user("persona")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nombre":"valor-privado-demasiado-largo","revisionEsperada":0}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields[*].field", hasItem("nombre")))
                .andExpect(jsonPath("$.fields[*].field", hasItem("revisionEsperada")))
                .andReturn();
        assertProblem(result, "VALIDACION");
        assertThat(result.getResponse().getContentAsString()).doesNotContain("valor-privado", "ValidationException");
    }

    @ParameterizedTest
    @MethodSource("invalidInputs")
    void rejectsJsonOutsideTheDtoContract(String payload, String code) throws Exception {
        var result = mvc.perform(post(PROBE).with(user("persona")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest()).andReturn();
        assertProblem(result, code);
        assertThat(result.getResponse().getContentAsString()).doesNotContain("secreto-ejemplo", "contraseña");
    }

    static Stream<Arguments> invalidInputs() {
        return Stream.of(
                Arguments.of("""
                        {"nombre":"Prueba","revisionEsperada":4,"empresaEfectiva":"secreto-ejemplo"}
                        """, "CAMPO_NO_PERMITIDO"),
                Arguments.of("""
                        {"nombre":"Prueba","revisionEsperada":4,"revisionEsperada":5}
                        """, "JSON_INVALIDO"),
                Arguments.of("""
                        {"nombre":"Prueba","revisionEsperada":"4"}
                        """, "JSON_INVALIDO"),
                Arguments.of("""
                        {"nombre":"Prueba","revisionEsperada":4.8}
                        """, "JSON_INVALIDO"),
                Arguments.of("""
                        {"nombre":123,"revisionEsperada":4}
                        """, "JSON_INVALIDO"),
                Arguments.of("""
                        {"nombre":"Prueba","revisionEsperada":4} {}
                        """, "JSON_INVALIDO"),
                Arguments.of("{", "JSON_INVALIDO"),
                Arguments.of("""
                        {"nombre":"Prueba"}
                        """, "VALIDACION"));
    }

    @Test
    void validatesRouteAndQueryParameters() throws Exception {
        var invalidId = mvc.perform(get(PROBE + "/ruta-privada").param("pagina", "1").with(user("persona")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields[0].field").value("id")).andReturn();
        assertProblem(invalidId, "VALIDACION");
        var invalidPage = mvc.perform(get(PROBE + "/" + UUID.randomUUID()).param("pagina", "-1").with(user("persona")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields[0].field").value("pagina")).andReturn();
        assertProblem(invalidPage, "VALIDACION");
    }

    @Test
    void distinguishesStaleRevisionAndInternalFailureWithoutLeakingExceptionMessages() throws Exception {
        var stale = mvc.perform(post(PROBE).with(user("persona")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nombre":"Prueba","revisionEsperada":3}
                                """))
                .andExpect(status().isConflict()).andReturn();
        assertProblem(stale, "REVISION_DESACTUALIZADA");
        var failed = mvc.perform(get(PROBE + "/fallo").with(user("persona")))
                .andExpect(status().isInternalServerError()).andReturn();
        assertProblem(failed, "ERROR_INTERNO");
        assertThat(stale.getResponse().getContentAsString()).doesNotContain("registro-privado");
        assertThat(failed.getResponse().getContentAsString()).doesNotContain("SELECT", "secreto-ejemplo");
    }

    @Test
    void preservesProtocolHeadersOnMethodAndContentTypeErrors() throws Exception {
        var method = mvc.perform(put(PROBE).with(user("persona")).with(csrf()))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", containsString("POST"))).andReturn();
        assertProblem(method, "METODO_NO_PERMITIDO");
        var mediaType = mvc.perform(post(PROBE).with(user("persona")).with(csrf())
                        .contentType(MediaType.TEXT_PLAIN).content("secreto-ejemplo"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(header().string("Accept", containsString("application/json"))).andReturn();
        assertProblem(mediaType, "TIPO_CONTENIDO_NO_ADMITIDO");
    }

    private void assertProblem(MvcResult result, String code) {
        var response = result.getResponse();
        String correlation = response.getHeader(CORRELATION);
        var problem = mapper.readTree(response.getContentAsByteArray());
        assertThat(response.getContentType()).startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertThat(problem.path("type").asString()).isEqualTo("about:blank");
        assertThat(problem.path("status").asInt()).isEqualTo(response.getStatus());
        assertThat(problem.path("code").asString()).isEqualTo(code);
        assertThat(problem.path("fields").isArray()).isTrue();
        assertThat(problem.path("correlationId").asString()).isEqualTo(correlation);
        assertThat(problem.path("instance").asString()).isEqualTo("urn:uuid:" + correlation);
        assertThat(UUID.fromString(correlation).version()).isEqualTo(4);
    }

    @TestComponent
    @RestController
    static class ProbeController {

        @PostMapping(path = PROBE, consumes = MediaType.APPLICATION_JSON_VALUE)
        Saved update(@Valid @RequestBody Update input) {
            if (input.revisionEsperada() != 4L) {
                throw new OptimisticLockingFailureException("registro-privado");
            }
            return new Saved(input.nombre(), 5L);
        }

        @GetMapping(PROBE + "/{id}")
        UUID list(@PathVariable UUID id, @RequestParam @Min(0) int pagina) {
            return id;
        }

        @GetMapping(PROBE + "/fallo")
        void fail() {
            throw new IllegalStateException("SELECT secreto-ejemplo");
        }
    }

    record Update(@NotBlank @Size(max = 12) String nombre, @NotNull @Positive Long revisionEsperada) {}
    record Saved(String nombre, long revision) {}
}
