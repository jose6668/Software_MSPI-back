package software.mspi;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.util.FileSystemUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import software.mspi.empresas.api.CompanyAccess;
import software.mspi.empresas.api.CompanyPermission;
import software.mspi.empresas.api.TenantUnitOfWork;
import software.mspi.empresas.internal.persistence.tenant.IsolationProbe;
import software.mspi.empresas.internal.tenancy.TenantSecretSource;
import software.mspi.identidad.api.SessionIdentity;
import tools.jackson.databind.json.JsonMapper;

import software.mspi.support.FakeClamd;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"mspi.tenancy.enabled=true", "mspi.tenancy.max-pools=2",
        "mspi.tenancy.connections-per-pool=2", "mspi.tenancy.connection-timeout=5s", "mspi.tenancy.idle-ttl=30s",
        "mspi.identity.attempts-per-address=60", "mspi.evidence.enabled=true"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({PostgresTestConfiguration.class, CompanyAuthorizationTests.Fixtures.class,
        CompanyAuthorizationTests.ProbeController.class})
class CompanyAuthorizationTests {

    private static final String MINE = "/api/v1/mis-empresas";
    private static final String CONTEXT = "/api/v1/empresas/{empresaId}/contexto";
    private static final String CONFIGURATION = "/api/v1/empresas/{empresaId}/configuracion";
    private static final String MEMBERS = "/api/v1/empresas/{empresaId}/membresias";
    private static final String RESPONSIBLES = "/api/v1/empresas/{empresaId}/responsables";
    private static final String ROLES = MEMBERS + "/{membresiaId}/roles";
    private static final String PROCESSES = "/api/v1/empresas/{empresaId}/procesos";
    private static final String ASSETS = "/api/v1/empresas/{empresaId}/activos";
    private static final String VALUATIONS = ASSETS + "/{activoId}/clasificaciones";
    private static final String CATALOGS = "/api/v1/empresas/{empresaId}/catalogos";
    private static final String METHODOLOGIES = "/api/v1/empresas/{empresaId}/metodologias";
    private static final String EVALUATIONS = "/api/v1/empresas/{empresaId}/evaluaciones";
    private static final String CONTROL_IMPLEMENTATIONS = "/api/v1/empresas/{empresaId}/implementaciones-control";
    private static final String CONTROL_IMPLEMENTATION = CONTROL_IMPLEMENTATIONS + "/{implementacionId}";
    private static final String CONTROL_VERSION = CONTROL_IMPLEMENTATION + "/versiones/{versionId}";
    private static final String CONTROL_EVIDENCE = CONTROL_VERSION + "/evidencias";
    private static final String EVALUATION_RESPONSES = EVALUATIONS + "/{evaluacionId}/respuestas";
    private static final String EVALUATION_EVIDENCE = EVALUATION_RESPONSES + "/{respuestaId}/evidencias";
    private static final String EVIDENCE_FILE = EVALUATION_EVIDENCE + "/{evidenciaId}/archivo";
    private static final String BUSINESS_RESPONSIBLES = "/api/v1/empresas/{empresaId}/responsables-negocio";
    private static final String PROBE = "/api/v1/pruebas-autorizacion/{empresaId}";
    private static final UUID SHARED = TenantIsolationFixture.SHARED_ID;

    @Autowired MockMvc mvc;
    @Autowired JsonMapper mapper;
    @Autowired PasswordEncoder encoder;
    @Autowired TenantIsolationFixture fixture;
    @Autowired TestDelivery delivery;
    @Autowired EvidenceVolume evidenceVolume;
    @Autowired FakeClamd clamd;

    @Test
    void listsOnlyOwnOperableCompaniesAndSelectsExplicitMinimalContext() throws Exception {
        var account = account();
        member(account, a(), "RA");
        member(account, b(), "AU", "GS");
        member(account, c(), "AE");
        var session = login(account);
        fixture.metadata(fixture.third, "ACTIVA", "999");
        try {
            mvc.perform(get(MINE)).andExpect(status().isUnauthorized());
            mvc.perform(get(MINE).session(session).param("tamano", "1"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.empresas.length()").value(1))
                    .andExpect(jsonPath("$.pagina").value(0)).andExpect(jsonPath("$.hayMas").value(true));
            mvc.perform(get(MINE).session(session).param("tamano", "1").param("pagina", "1"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.empresas.length()").value(1))
                    .andExpect(jsonPath("$.hayMas").value(false));
            mvc.perform(get(MINE).session(session).param("tamano", "101"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDACION"));
            mvc.perform(get(CONTEXT, c()).session(session)).andExpect(status().isForbidden());
            var view = mvc.perform(get(CONTEXT, a()).session(session)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.empresaId").value(a().toString()))
                    .andExpect(jsonPath("$.roles[0]").value("RA")).andReturn();
            assertThat(mapper.readTree(view.getResponse().getContentAsByteArray()).propertyNames())
                    .containsExactlyInAnyOrder("empresaId", "nombre", "zonaHoraria", "roles");
            mvc.perform(get(CONTEXT, b()).session(session)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.roles[0]").value("AU"))
                    .andExpect(jsonPath("$.roles[1]").value("GS"));
            mvc.perform(get(PROBE + "/registros/{id}", a(), SHARED).session(session))
                    .andExpect(status().isForbidden());
        } finally {
            fixture.metadata(fixture.third, "ACTIVA", "9");
        }
    }

    @Test
    void grantsCombinedRolesLocallyAndRemovesEditOnNextRequest() throws Exception {
        var account = account();
        member(account, a(), "AE", "GS");
        UUID membershipB = member(account, b(), "AU");
        var session = login(account);
        read(session, a(), SHARED).andExpect(status().isOk()).andExpect(jsonPath("$.valor").value("A"));
        read(session, b(), SHARED).andExpect(status().isOk()).andExpect(jsonPath("$.valor").value("B"));
        edit(session, b(), "rejected").andExpect(status().isForbidden());

        sql("INSERT INTO comun.membresia_roles VALUES (?, 'GS')", membershipB);
        try {
            mvc.perform(put(PROBE + "/registros/{id}", b(), SHARED).session(session)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"valor\":\"sin-csrf\"}"))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_INVALIDO"));
            edit(session, b(), "B-updated").andExpect(status().isNoContent());
            read(session, a(), SHARED).andExpect(status().isOk()).andExpect(jsonPath("$.valor").value("A"));
            read(session, b(), SHARED).andExpect(status().isOk()).andExpect(jsonPath("$.valor").value("B-updated"));
        } finally {
            sql("DELETE FROM comun.membresia_roles WHERE membresia_id = ? AND rol_codigo = 'GS'", membershipB);
            sql("UPDATE " + fixture.second.scope().schema() + ".isolation_probe SET valor = 'B' WHERE id = ?", SHARED);
        }
        edit(session, b(), "rejected-again").andExpect(status().isForbidden());
        read(session, b(), SHARED).andExpect(status().isOk()).andExpect(jsonPath("$.valor").value("B"));
    }

    @Test
    void rejectsForeignObjectAndReflectsSuspensionAndRevocationInTheSameSession() throws Exception {
        var account = account();
        UUID membershipB = member(account, b(), "GS");
        member(account, a(), "AE");
        var session = login(account);
        UUID onlyB = UUID.randomUUID();
        sql("INSERT INTO " + fixture.second.scope().schema()
                + ".isolation_probe (id, empresa_id, valor) VALUES (?, ?, 'privado-b')", onlyB, b());
        read(session, a(), onlyB).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NO_ENCONTRADO"));
        read(session, b(), onlyB).andExpect(status().isOk()).andExpect(jsonPath("$.valor").value("privado-b"));

        fixture.metadata(fixture.first, "SUSPENDIDA", "9");
        try {
            mvc.perform(get(CONTEXT, a()).session(session)).andExpect(status().isForbidden());
            read(session, a(), SHARED).andExpect(status().isForbidden());
            read(session, b(), SHARED).andExpect(status().isOk());
        } finally {
            fixture.metadata(fixture.first, "ACTIVA", "9");
        }

        sql("UPDATE comun.membresias SET estado = 'REVOCADA', revocada_en = CURRENT_TIMESTAMP WHERE id = ?", membershipB);
        read(session, b(), onlyB).andExpect(status().isForbidden());
        mvc.perform(get(MINE).session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.empresas.length()").value(1))
                .andExpect(jsonPath("$.empresas[0].empresaId").value(a().toString()));

        sql("UPDATE comun.usuarios SET estado_cuenta = 'DESHABILITADA' WHERE id = ?", account.id());
        mvc.perform(get(CONTEXT, a()).session(session)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("NO_AUTENTICADO"));
    }

    @Test
    void platformAttributionAndForgedActorCannotResolveTenantCredentials() throws Exception {
        var ap = account();
        var other = account();
        member(other, c(), "AE");
        sql("INSERT INTO comun.atribuciones_plataforma VALUES (?, 'AP')", ap.id());
        var session = login(ap);
        int lookups = fixture.secretLoads.get();
        mvc.perform(get(MINE).session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.empresas.length()").value(0));
        mvc.perform(get(CONTEXT, c()).session(session)).andExpect(status().isForbidden());
        read(session, c(), SHARED).andExpect(status().isForbidden());
        mvc.perform(get(PROBE + "/actor/{actorId}", c(), other.id()).session(session))
                .andExpect(status().isForbidden());
        assertThat(fixture.secretLoads.get()).isEqualTo(lookups);
    }

    @Test
    void combinedRolesCannotDecideOwnDeliveryButMayReadIt() throws Exception {
        var author = account();
        var reviewer = account();
        member(author, a(), "GS", "RA");
        member(reviewer, a(), "AE");
        delivery.responsible.set(author.id());
        var authorSession = login(author);
        var reviewerSession = login(reviewer);

        mvc.perform(get(PROBE + "/entrega", a()).session(authorSession)).andExpect(status().isOk());
        for (String decision : new String[]{"aprobar", "devolver"}) {
            mvc.perform(post(PROBE + "/entrega/{decision}", a(), decision).session(authorSession).with(csrf()))
                    .andExpect(status().isForbidden());
            mvc.perform(post(PROBE + "/entrega/{decision}", a(), decision).session(reviewerSession).with(csrf()))
                    .andExpect(status().isNoContent());
        }
    }

    @Test
    void databaseEnforcesUniqueMembershipsAndNonemptyEnterpriseRoles() throws Exception {
        var account = account();
        UUID membership = member(account, a(), "AE");
        assertSqlState(() -> member(account, a(), "GS"), "23505");
        assertSqlState(() -> member(account, b()), "23514");
        assertSqlState(() -> member(account, c(), "AP"), "23503");
        assertSqlState(() -> sql("INSERT INTO comun.membresia_roles VALUES (?, 'AE')", membership), "23505");
        assertSqlState(() -> sql("DELETE FROM comun.membresia_roles WHERE membresia_id = ?", membership), "23503");
        UUID membershipB = member(account, b(), "GS");
        String schema = fixture.first.scope().schema();
        String table = schema + ".membresia_probe";
        sql("CREATE TABLE " + table + " (empresa_id uuid NOT NULL REFERENCES " + schema
                + ".contexto_empresa(empresa_id), membresia_id uuid NOT NULL,"
                + " FOREIGN KEY (empresa_id, membresia_id) REFERENCES comun.membresias(empresa_id, id))");
        sql("GRANT INSERT ON " + table + " TO " + fixture.first.scope().runtimeRole());
        try (var runtime = fixture.database.runtime(); var statement = runtime.createStatement()) {
            assertSqlState(() -> statement.executeUpdate("DELETE FROM comun.membresia_roles"), "42501");
        }
        try (var runtime = fixture.database.connect(fixture.first.scope().runtimeRole(), fixture.first.runtimePassword());
                var statement = runtime.createStatement();
                var insert = runtime.prepareStatement("INSERT INTO " + table + " VALUES (?, ?)")) {
            assertSqlState(() -> statement.executeQuery("SELECT id FROM comun.membresias"), "42501");
            insert.setObject(1, a());
            insert.setObject(2, membership);
            assertThat(insert.executeUpdate()).isEqualTo(1);
            insert.setObject(2, membershipB);
            assertSqlState(insert::executeUpdate, "23503");
        }
    }

    @Test
    void configurationEnforcesRolesAndRevisionAndRecordsHistory() throws Exception {
        UUID company = operableCompany();
        var administrator = account();
        member(administrator, company, "AE");
        var gs = account();
        member(gs, company, "GS");
        var ra = account();
        member(ra, company, "RA");
        var administratorSession = login(administrator);
        var gsSession = login(gs);
        var raSession = login(ra);
        mvc.perform(get(CONFIGURATION, company)).andExpect(status().isUnauthorized());
        mvc.perform(get(CONFIGURATION, company).session(raSession)).andExpect(status().isForbidden());
        mvc.perform(get(CONTEXT, company).session(raSession)).andExpect(status().isOk());
        mvc.perform(get(CONFIGURATION, company).session(gsSession)).andExpect(status().isOk())
                .andExpect(jsonPath("$.zonaHoraria").value("America/Bogota"));

        var input = Map.<String, Object>of("nombre", "Empresa actualizada", "descripcion", "Descripción",
                "zonaHoraria", "America/Lima", "revisionEsperada", 1);
        configure(company, gsSession, input).andExpect(status().isForbidden());
        var forbidden = new HashMap<>(input);
        forbidden.put("esquemaInterno", "emp_otro");
        configure(company, administratorSession, forbidden).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CAMPO_NO_PERMITIDO"));
        var invalid = new HashMap<>(input);
        invalid.put("zonaHoraria", "Zona/invalida");
        configure(company, administratorSession, invalid).andExpect(status().isBadRequest());
        var updated = configure(company, administratorSession, input).andExpect(status().isNoContent()).andReturn();
        configure(company, administratorSession, input).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REVISION_DESACTUALIZADA"));
        mvc.perform(get(CONFIGURATION, company).session(gsSession)).andExpect(status().isOk())
                .andExpect(jsonPath("$.nombre").value("Empresa actualizada"))
                .andExpect(jsonPath("$.zonaHoraria").value("America/Lima"))
                .andExpect(jsonPath("$.revision").value(2))
                .andExpect(jsonPath("$.esquemaInterno").doesNotExist());
        assertThat(count("""
                SELECT count(*) FROM comun.eventos_administracion_empresa
                WHERE empresa_id = ? AND actor_id = ? AND correlacion_id = ? AND operacion = 'CONFIGURAR'
                  AND anterior->>'zonaHoraria' = 'America/Bogota' AND nuevo->>'zonaHoraria' = 'America/Lima'
                """, company, administrator.id(), UUID.fromString(updated.getResponse().getHeader("X-Correlation-Id"))))
                .isEqualTo(1);
    }

    @Test
    void selectorReturnsOnlyMinimalLocalEligibleMembers() throws Exception {
        UUID company = operableCompany();
        var administrator = account();
        var gs = account();
        var au = account();
        var ra = account();
        UUID aeId = member(administrator, company, "AE");
        UUID gsId = member(gs, company, "GS");
        member(au, company, "AU");
        UUID raId = member(ra, company, "RA");
        member(gs, b(), "AE");
        var gsSession = login(gs);
        var aeSession = login(administrator);
        mvc.perform(get(MEMBERS, company).session(gsSession)).andExpect(status().isForbidden());
        mvc.perform(get(MEMBERS, company).session(aeSession).param("tamano", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.elementos.length()").value(1))
                .andExpect(jsonPath("$.hayMas").value(true));
        mvc.perform(get(RESPONSIBLES, company).session(login(ra))).andExpect(status().isForbidden());
        var response = mvc.perform(get(RESPONSIBLES, company).session(gsSession)).andExpect(status().isOk()).andReturn();
        var rows = mapper.readTree(response.getResponse().getContentAsByteArray()).path("elementos");
        assertThat(rows.valueStream().map(row -> row.path("membresiaId").asString()).toList())
                .containsExactlyInAnyOrder(aeId.toString(), gsId.toString(), raId.toString());
        rows.forEach(row -> assertThat(row.propertyNames()).containsExactlyInAnyOrder("membresiaId", "nombreVisible"));
    }

    @Test
    void roleChangeAndRevocationApplyToAnOpenSessionOnlyInTheTargetCompany() throws Exception {
        var administrator = account();
        member(administrator, b(), "AE");
        var target = account();
        UUID foreignMembership = member(target, a(), "AU");
        UUID membership = member(target, b(), "AU", "GS");
        var ae = login(administrator);
        var targetSession = login(target);
        mvc.perform(put(ROLES, b(), membership).session(ae).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"revisionEsperada\":1,\"motivo\":\"Revisión\",\"roles\":[\"AU\"]}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_INVALIDO"));
        for (var invalid : List.of(List.of(), List.of("AP"),
                List.of("AU", "AU"), List.of(0))) {
            changeRoles(ae, b(), membership, 1, invalid).andExpect(status().isBadRequest());
        }
        changeRoles(ae, b(), foreignMembership, 1, List.of("AU")).andExpect(status().isNotFound());
        changeRoles(ae, b(), membership, 1, List.of("AU")).andExpect(status().isNoContent());
        changeRoles(ae, b(), membership, 1, List.of("GS")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REVISION_DESACTUALIZADA"));
        edit(targetSession, b(), "denegado").andExpect(status().isForbidden());
        read(targetSession, b(), SHARED).andExpect(status().isOk());

        revoke(ae, b(), membership, 2).andExpect(status().isNoContent());
        changeRoles(ae, b(), membership, 3, List.of("AE")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MEMBRESIA_REVOCADA"));
        read(targetSession, b(), SHARED).andExpect(status().isForbidden());
        read(targetSession, a(), SHARED).andExpect(status().isOk());
        assertThat(count("""
                SELECT count(*) FROM comun.eventos_administracion_empresa
                WHERE membresia_id = ? AND actor_id = ? AND motivo = 'Revisión'
                  AND operacion = 'CAMBIAR_ROLES'
                  AND anterior->'roles' = '["AU","GS"]'::jsonb AND nuevo->'roles' = '["AU"]'::jsonb
                """, membership, administrator.id())).isEqualTo(1);
        assertThat(count("""
                SELECT count(*) FROM comun.eventos_administracion_empresa
                WHERE membresia_id = ? AND operacion = 'REVOCAR_MEMBRESIA' AND nuevo->>'estado' = 'REVOCADA'
                """, membership)).isEqualTo(1);
    }

    @Test
    void concurrentSelfDemotionAndRevocationCannotRemoveBothAdministrators() throws Exception {
        UUID company = operableCompany();
        var first = account();
        var second = account();
        UUID firstMember = member(first, company, "AE");
        UUID secondMember = member(second, company, "AE");
        var firstSession = login(first);
        var secondSession = login(second);
        var barrier = new CyclicBarrier(2);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var demotion = executor.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return changeRoles(firstSession, company, firstMember, 1, List.of("AU")).andReturn();
            });
            var revocation = executor.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return revoke(secondSession, company, secondMember, 1).andReturn();
            });
            var responses = List.of(demotion.get(15, TimeUnit.SECONDS), revocation.get(15, TimeUnit.SECONDS));
            assertThat(responses).extracting(result -> result.getResponse().getStatus())
                    .containsExactlyInAnyOrder(204, 409);
            var rejected = responses.stream().filter(result -> result.getResponse().getStatus() == 409).findFirst().orElseThrow();
            assertThat(mapper.readTree(rejected.getResponse().getContentAsByteArray()).path("code").asString())
                    .isEqualTo("ULTIMO_AE");
        } finally {
            executor.shutdownNow();
        }
        assertThat(count("""
                SELECT count(*) FROM comun.membresias m JOIN comun.membresia_roles r ON m.id = r.membresia_id
                WHERE m.empresa_id = ? AND m.estado = 'ACTIVA' AND r.rol_codigo = 'AE'
                """, company)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM comun.eventos_administracion_empresa WHERE empresa_id = ?", company))
                .isEqualTo(1);
        try (var runtime = fixture.database.runtime(); var statement = runtime.createStatement()) {
            assertSqlState(() -> statement.executeQuery("SELECT anterior FROM comun.eventos_administracion_empresa"), "42501");
            assertSqlState(() -> statement.executeUpdate("UPDATE comun.membresias SET revision = 10"), "42501");
        }
    }

    private UUID operableCompany() throws SQLException {
        UUID company = fixture.database.reserveTenant().scope().companyId();
        sql("UPDATE comun.empresas SET estado = 'ACTIVA', version_estructura = '9' WHERE id = ?", company);
        return company;
    }

    private ResultActions configure(UUID company, MockHttpSession session, Map<String, Object> input) throws Exception {
        return mvc.perform(put(CONFIGURATION, company).session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(input)));
    }

    private ResultActions changeRoles(MockHttpSession session, UUID company, UUID member, long revision,
                                      List<?> roles) throws Exception {
        return mvc.perform(put(ROLES, company, member).session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("revisionEsperada", revision, "roles", roles, "motivo", "Revisión"))));
    }

    private ResultActions revoke(MockHttpSession session, UUID company, UUID member, long revision) throws Exception {
        return mvc.perform(post(MEMBERS + "/{membresiaId}/revocar", company, member).session(session).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("revisionEsperada", revision, "motivo", "Salida"))));
    }

    private long count(String sql, Object... values) throws SQLException {
        try (var connection = fixture.database.admin(); var statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) {
                statement.setObject(index + 1, values[index]);
            }
            try (var rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getLong(1);
            }
        }
    }

    @Test
    void businessCatalogHonorsRolesCsrfAndCompanyScope() throws Exception {
        var editor = account();
        var auditor = account();
        var responsible = account();
        member(editor, a(), "GS");
        member(auditor, a(), "AU");
        member(responsible, a(), "RA");
        var gs = login(editor);
        var au = login(auditor);
        var ra = login(responsible);
        mvc.perform(get(PROCESSES, a())).andExpect(status().isUnauthorized());
        mvc.perform(get(PROCESSES, a()).session(au)).andExpect(status().isOk());
        mvc.perform(get(BUSINESS_RESPONSIBLES, a()).session(ra)).andExpect(status().isForbidden());
        mvc.perform(get(PROCESSES, b()).session(gs)).andExpect(status().isForbidden());
        catalogPost(PROCESSES, a(), au, Map.of("nombre", "Denegado")).andExpect(status().isForbidden());
        mvc.perform(post(PROCESSES, a()).session(gs).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombre\":\"Sin CSRF\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_INVALIDO"));
        catalogPost(PROCESSES, a(), gs, Map.of("nombre", " ", "incluidoAlcance", true))
                .andExpect(status().isBadRequest());
        catalogPost(BUSINESS_RESPONSIBLES, a(), gs, Map.of("nombre", "Tipo inválido", "tipo", "AP"))
                .andExpect(status().isBadRequest());
        var created = catalogPost(PROCESSES, a(), gs, Map.of("nombre", "Operación"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.estado").value("ACTIVO")).andReturn();
        UUID id = id(created, "procesoId");
        mvc.perform(get(PROCESSES + "/{id}", a(), id).session(au)).andExpect(status().isOk());
        assertThat(count("SELECT count(*) FROM " + fixture.first.scope().schema()
                + ".eventos_actividad WHERE objeto_id = ? AND actor_id = ? AND accion = 'CREAR' AND correlacion_id = ?",
                id, editor.id(), UUID.fromString(created.getResponse().getHeader("X-Correlation-Id")))).isOne();
    }

    @Test
    void refusesForeignReferencesAtApiAndDatabaseAndUsesLocalCredentials() throws Exception {
        var editor = account();
        UUID memberA = member(editor, a(), "GS");
        UUID memberB = member(editor, b(), "GS");
        var session = login(editor);
        var foreign = catalogPost(BUSINESS_RESPONSIBLES, b(), session,
                Map.of("nombre", "Operaciones", "tipo", "AREA", "membresiaId", memberB))
                .andExpect(status().isCreated()).andReturn();
        UUID foreignResponsible = id(foreign, "responsableId");
        catalogPost(PROCESSES, a(), session, Map.of("nombre", "Proceso ajeno", "responsableId", foreignResponsible))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REFERENCIA_NO_DISPONIBLE"));
        catalogPost(BUSINESS_RESPONSIBLES, a(), session, Map.of("nombre", "Ajeno", "tipo", "AREA", "membresiaId", memberB))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REFERENCIA_NO_DISPONIBLE"));
        mvc.perform(get(BUSINESS_RESPONSIBLES + "/{id}", a(), foreignResponsible).session(session))
                .andExpect(status().isNotFound());
        catalogPost(BUSINESS_RESPONSIBLES, a(), session, Map.of("nombre", "Local", "tipo", "PERSONA", "membresiaId", memberA))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.membresiaId").value(memberA.toString()));
        try (var connection = fixture.database.connect(fixture.first.scope().runtimeRole(), fixture.first.runtimePassword());
                var statement = connection.createStatement()) {
            assertSqlState(() -> statement.executeQuery("SELECT * FROM " + fixture.second.scope().schema() + ".procesos"), "42501");
            assertSqlState(() -> statement.executeQuery("SELECT * FROM comun.membresias"), "42501");
            assertSqlState(() -> statement.executeUpdate("DELETE FROM procesos"), "42501");
            assertSqlState(() -> statement.executeUpdate("UPDATE eventos_actividad SET accion = 'ALTERAR'"), "42501");
            try (var insert = connection.prepareStatement("""
                    INSERT INTO responsables_negocio(id, empresa_id, nombre, tipo, membresia_id, estado, revision)
                    VALUES (?, ?, 'Ajeno', 'AREA', ?, 'ACTIVO', 0)
                    """)) {
                insert.setObject(1, UUID.randomUUID());
                insert.setObject(2, a());
                insert.setObject(3, memberB);
                assertSqlState(insert::executeUpdate, "23503");
            }
        }
    }

    @Test
    void archivesWithoutLosingDependenciesAndRejectsNewArchivedAssignments() throws Exception {
        var editor = account();
        member(editor, b(), "AE");
        var session = login(editor);
        var responsible = catalogPost(BUSINESS_RESPONSIBLES, b(), session, Map.of("nombre", "Mesa", "tipo", "CARGO"))
                .andExpect(status().isCreated()).andReturn();
        UUID responsibleId = id(responsible, "responsableId");
        var created = catalogPost(PROCESSES, b(), session, Map.of("nombre", "Atención", "responsableId", responsibleId))
                .andExpect(status().isCreated()).andReturn();
        UUID processId = id(created, "procesoId");
        mvc.perform(get(PROCESSES, b()).session(session).param("responsableId", responsibleId.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.elementos[0].procesoId").value(processId.toString()));
        catalogPost(BUSINESS_RESPONSIBLES + "/{id}/archivar", b(), session,
                Map.of("revisionEsperada", 0, "motivo", "Rotación"), responsibleId)
                .andExpect(status().isOk()).andExpect(jsonPath("$.estado").value("ARCHIVADO"))
                .andExpect(jsonPath("$.revision").value(1));
        mvc.perform(get(PROCESSES + "/{id}", b(), processId).session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.responsableId").value(responsibleId.toString()));
        catalogPost(PROCESSES, b(), session, Map.of("nombre", "Nuevo", "responsableId", responsibleId))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REFERENCIA_NO_DISPONIBLE"));
        catalogPut(PROCESSES, b(), processId, session,
                Map.of("revisionEsperada", 0, "nombre", "Atención editada", "responsableId", responsibleId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(1));
        catalogPost(PROCESSES + "/{id}/archivar", b(), session, Map.of("revisionEsperada", 1, "motivo", "Cierre"), processId)
                .andExpect(status().isOk()).andExpect(jsonPath("$.estado").value("ARCHIVADO"));
        catalogPut(PROCESSES, b(), processId, session, Map.of("revisionEsperada", 2, "nombre", "No editable"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REGISTRO_ARCHIVADO"));
        assertThat(count("""
                SELECT count(*) FROM %s.eventos_actividad
                WHERE objeto_id = ? AND estado_anterior = 'ACTIVO' AND estado_nuevo = 'ARCHIVADO'
                    AND motivo = 'Cierre'
                """.formatted(fixture.second.scope().schema()), processId)).isOne();
    }

    @Test
    void concurrentCatalogUpdatesRejectAStaleRevisionAndAuditOnlyTheWinner() throws Exception {
        var editor = account();
        member(editor, a(), "GS");
        var session = login(editor);
        var entry = catalogPost(BUSINESS_RESPONSIBLES, a(), session, Map.of("nombre", "Equipo", "tipo", "AREA"))
                .andExpect(status().isCreated()).andReturn();
        UUID id = id(entry, "responsableId");
        var barrier = new CyclicBarrier(2);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return catalogPut(BUSINESS_RESPONSIBLES, a(), id, session,
                        Map.of("nombre", "Equipo A", "tipo", "AREA", "revisionEsperada", 0)).andReturn();
            });
            var second = executor.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return catalogPut(BUSINESS_RESPONSIBLES, a(), id, session,
                        Map.of("nombre", "Equipo B", "tipo", "AREA", "revisionEsperada", 0)).andReturn();
            });
            assertThat(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)))
                    .extracting(result -> result.getResponse().getStatus()).containsExactlyInAnyOrder(200, 409);
        } finally {
            executor.shutdownNow();
        }
        assertThat(count("SELECT count(*) FROM " + fixture.first.scope().schema()
                + ".eventos_actividad WHERE objeto_id = ? AND accion = 'EDITAR'", id)).isOne();
    }

    private ResultActions catalogPost(String path, UUID company, MockHttpSession session, Object input, Object... tail)
            throws Exception {
        var variables = new java.util.ArrayList<>();
        variables.add(company);
        variables.addAll(List.of(tail));
        return mvc.perform(post(path, variables.toArray()).session(session).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(input)));
    }

    private ResultActions catalogPut(String path, UUID company, UUID id, MockHttpSession session, Object input)
            throws Exception {
        return mvc.perform(put(path + "/{id}", company, id).session(session).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(input)));
    }

    private UUID id(org.springframework.test.web.servlet.MvcResult result, String field) {
        return UUID.fromString(mapper.readTree(result.getResponse().getContentAsByteArray()).path(field).asString());
    }

    @Test
    void assetCodeIsUniqueByCompanyAndRemainsReservedAfterArchiving() throws Exception {
        var editor = account();
        member(editor, a(), "AE");
        member(editor, b(), "AE");
        var session = login(editor);
        var aRefs = assetReferences(a(), session);
        var bRefs = assetReferences(b(), session);

        var first = catalogPost(ASSETS, a(), session, assetCreate("  Réd-01_  ", aRefs))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.codigo").value("Réd-01_"))
                .andExpect(jsonPath("$.estadoClasificacion").value("PENDIENTE")).andReturn();
        UUID firstId = id(first, "activoId");
        catalogPost(ASSETS, a(), session, assetCreate("réD-01_", aRefs))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CODIGO_ACTIVO_DUPLICADO"));
        catalogPost(ASSETS, b(), session, assetCreate("réd-01_", bRefs)).andExpect(status().isCreated());
        catalogPost(ASSETS, a(), session, assetCreate("Red-01_", aRefs)).andExpect(status().isCreated());
        catalogPost(ASSETS, a(), session, assetCreate("Réd 01_", aRefs)).andExpect(status().isCreated());
        mvc.perform(get(ASSETS, a()).session(session).param("texto", "_").param("tamano", "1")
                        .param("tipo", "RED").param("procesoId", aRefs.get("procesoId").toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.elementos.length()").value(1))
                .andExpect(jsonPath("$.hayMas").value(true));
        mvc.perform(get(ASSETS, a()).session(session).param("texto", "%"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.elementos.length()").value(0));
        catalogPost(ASSETS + "/{id}/archivar", a(), session, Map.of("revisionEsperada", 0, "motivo", "Retiro"), firstId)
                .andExpect(status().isOk());
        catalogPost(ASSETS, a(), session, assetCreate("RÉD-01_", aRefs))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CODIGO_ACTIVO_DUPLICADO"));
        mvc.perform(get(ASSETS, a()).session(session).param("estado", "ARCHIVADO").param("texto", "réd-01"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.elementos[0].activoId").value(firstId.toString()));
        try (var connection = fixture.database.connect(fixture.first.scope().runtimeRole(), fixture.first.runtimePassword());
                var statement = connection.createStatement()) {
            assertSqlState(() -> statement.executeUpdate("UPDATE activos SET codigo = 'Otro'"), "42501");
            assertSqlState(() -> statement.executeUpdate("DELETE FROM activos"), "42501");
            assertSqlState(() -> statement.executeQuery("SELECT * FROM eventos_actividad"), "42501");
            assertSqlState(() -> statement.executeQuery("SELECT * FROM " + fixture.second.scope().schema() + ".activos"), "42501");
        }
    }

    @Test
    void assetEndpointsRestrictRolesAndRejectCrossCompanyReferences() throws Exception {
        var editor = account();
        var auditor = account();
        var assigned = account();
        member(editor, a(), "GS");
        member(editor, b(), "GS");
        member(auditor, a(), "AU");
        member(assigned, a(), "RA");
        var session = login(editor);
        var au = login(auditor);
        var ra = login(assigned);
        var local = assetReferences(a(), session);
        var foreign = assetReferences(b(), session);

        mvc.perform(get(ASSETS, a())).andExpect(status().isUnauthorized());
        mvc.perform(get(ASSETS, a()).session(ra)).andExpect(status().isForbidden());
        catalogPost(ASSETS, a(), au, assetCreate("PERMISO-01", local)).andExpect(status().isForbidden());
        mvc.perform(post(ASSETS, a()).session(session).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(assetCreate("PERMISO-01", local))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_INVALIDO"));
        for (String key : List.of("procesoId", "propietarioId", "custodioId")) {
            var mixed = new HashMap<>(local);
            mixed.put(key, foreign.get(key.equals("custodioId") ? "propietarioId" : key));
            catalogPost(ASSETS, a(), session, assetCreate("PERMISO-01", mixed))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REFERENCIA_NO_DISPONIBLE"));
        }
        var invalid = new HashMap<>(assetCreate("PERMISO-01", local));
        invalid.put("tipo", "OTRO");
        catalogPost(ASSETS, a(), session, invalid).andExpect(status().isBadRequest());
        var created = catalogPost(ASSETS, a(), session, assetCreate("PERMISO-01", local))
                .andExpect(status().isCreated()).andReturn();
        UUID id = id(created, "activoId");
        mvc.perform(get(ASSETS + "/{id}", a(), id).session(au)).andExpect(status().isOk());
        mvc.perform(get(ASSETS + "/{id}", b(), id).session(session)).andExpect(status().isNotFound());
        mvc.perform(get(ASSETS + "/{id}/historial", a(), id).session(au)).andExpect(status().isForbidden());
        mvc.perform(get(ASSETS + "/{id}/historial", a(), id).session(session)).andExpect(status().isForbidden());
    }

    @Test
    void assetRestorationRevalidatesReferencesAndPreservesAuthorizedHistory() throws Exception {
        var editor = account();
        member(editor, b(), "AE");
        var session = login(editor);
        var original = assetReferences(b(), session);
        var replacement = assetReferences(b(), session);
        var created = catalogPost(ASSETS, b(), session, assetCreate("CICLO-01", original))
                .andExpect(status().isCreated()).andReturn();
        UUID id = id(created, "activoId");

        catalogPut(ASSETS, b(), id, session, assetEdit(0, "Activo editado", original))
                .andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(1));
        catalogPut(ASSETS, b(), id, session, assetEdit(0, "Revisión obsoleta", original))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REVISION_DESACTUALIZADA"));
        catalogPost(ASSETS + "/{id}/archivar", b(), session, Map.of("revisionEsperada", 1, "motivo", " "), id)
                .andExpect(status().isBadRequest());
        catalogPost(ASSETS + "/{id}/archivar", b(), session, Map.of("revisionEsperada", 1, "motivo", "Mantenimiento"), id)
                .andExpect(status().isOk()).andExpect(jsonPath("$.estado").value("ARCHIVADO"));
        catalogPut(ASSETS, b(), id, session, assetEdit(2, "Archivado", original))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REGISTRO_ARCHIVADO"));

        catalogPost(PROCESSES + "/{id}/archivar", b(), session,
                Map.of("revisionEsperada", 0, "motivo", "Cambio de proceso"), original.get("procesoId"))
                .andExpect(status().isOk());
        catalogPost(ASSETS, b(), session, assetCreate("CICLO-NUEVO", original))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REFERENCIA_NO_DISPONIBLE"));
        catalogPost(ASSETS + "/{id}/restaurar", b(), session, Map.of("revisionEsperada", 2, "motivo", "Verificado"), id)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REFERENCIA_NO_DISPONIBLE"));
        var restored = catalogPost(ASSETS + "/{id}/restaurar", b(), session,
                Map.of("revisionEsperada", 2, "motivo", "Reasignado", "referencias", replacement), id)
                .andExpect(status().isOk()).andExpect(jsonPath("$.activoId").value(id.toString()))
                .andExpect(jsonPath("$.estado").value("ACTIVO")).andExpect(jsonPath("$.revision").value(3))
                .andExpect(jsonPath("$.procesoId").value(replacement.get("procesoId").toString())).andReturn();

        mvc.perform(get(ASSETS + "/{id}/historial", b(), id).session(session).param("tamano", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.hayMas").value(true))
                .andExpect(jsonPath("$.elementos[0].accion").value("RESTAURAR"))
                .andExpect(jsonPath("$.elementos[0].actorId").value(editor.id().toString()))
                .andExpect(jsonPath("$.elementos[0].correlationId").value(restored.getResponse().getHeader("X-Correlation-Id")))
                .andExpect(jsonPath("$.elementos[0].referencias.anteriores.procesoId").value(original.get("procesoId").toString()))
                .andExpect(jsonPath("$.elementos[0].referencias.nuevas.procesoId").value(replacement.get("procesoId").toString()))
                .andExpect(jsonPath("$.elementos[1].accion").value("ARCHIVAR"))
                .andExpect(jsonPath("$.elementos[1].motivo").value("Mantenimiento"));
    }

    @Test
    void concurrentEquivalentAssetCodesCreateOnlyOneAssetAndOneEvent() throws Exception {
        var editor = account();
        member(editor, a(), "AE");
        var session = login(editor);
        var references = assetReferences(a(), session);
        var barrier = new CyclicBarrier(2);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return catalogPost(ASSETS, a(), session, assetCreate(" Carrera-01 ", references)).andReturn();
            });
            var second = executor.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return catalogPost(ASSETS, a(), session, assetCreate("carrera-01", references)).andReturn();
            });
            var responses = List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
            assertThat(responses).extracting(result -> result.getResponse().getStatus()).containsExactlyInAnyOrder(201, 409);
            var created = responses.stream().filter(result -> result.getResponse().getStatus() == 201).findFirst().orElseThrow();
            UUID asset = id(created, "activoId");
            assertThat(count("SELECT count(*) FROM " + fixture.first.scope().schema()
                    + ".eventos_actividad WHERE objeto_id = ? AND tipo_objeto = 'ACTIVO'", asset)).isOne();
            var editFirst = executor.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return catalogPut(ASSETS, a(), asset, session, assetEdit(0, "Edición A", references)).andReturn();
            });
            var editSecond = executor.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return catalogPut(ASSETS, a(), asset, session, assetEdit(0, "Edición B", references)).andReturn();
            });
            assertThat(List.of(editFirst.get(15, TimeUnit.SECONDS), editSecond.get(15, TimeUnit.SECONDS)))
                    .extracting(result -> result.getResponse().getStatus()).containsExactlyInAnyOrder(200, 409);
            assertThat(count("SELECT count(*) FROM " + fixture.first.scope().schema()
                    + ".eventos_actividad WHERE objeto_id = ? AND accion = 'EDITAR'", asset)).isOne();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void classificationAppliesTheApprovedMaximumAndLeavesPartialInputsPending() throws Exception {
        var editor = account();
        member(editor, a(), "GS");
        var session = login(editor);
        var refs = assetReferences(a(), session);
        UUID asset = classifiedAsset(a(), session, "CID-PEND", refs);
        startValuation(a(), asset, session, Map.of("confidencialidad", 4), null)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields[0].field").value("entradas.confidencialidad"));
        startValuation(a(), asset, session, Map.of("criticidad", 5), null)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("CAMPO_NO_PERMITIDO"));
        var started = startValuation(a(), asset, session, Map.of("confidencialidad", 5, "disponibilidad", 1), null)
                .andExpect(status().isCreated()).andExpect(jsonPath("$.estado").value("PENDIENTE"))
                .andExpect(jsonPath("$.criticidad").isEmpty()).andReturn();
        UUID draft = id(started, "valoracionId");
        confirmValuation(a(), asset, draft, session, 0).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CLASIFICACION_INCOMPLETA"));
        mvc.perform(get(ASSETS + "/{id}", a(), asset).session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.estadoClasificacion").value("PENDIENTE"));
        editValuation(a(), asset, draft, session, 0, completeInputs(5, 1, 1))
                .andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(1));
        confirmValuation(a(), asset, draft, session, 0).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REVISION_DESACTUALIZADA"));
        confirmValuation(a(), asset, draft, session, 1).andExpect(status().isOk())
                .andExpect(jsonPath("$.nivel").value("ALTA")).andExpect(jsonPath("$.criticidad").value(5))
                .andExpect(jsonPath("$.metodologia").value("CID_MAX_V1"))
                .andExpect(jsonPath("$.confirmadaPor").value(editor.id().toString()));

        int[][] examples = {{1, 1, 1, 1}, {3, 1, 1, 3}, {3, 5, 5, 5}};
        String[] levels = {"BAJA", "MEDIA", "ALTA"};
        for (int index = 0; index < examples.length; index++) {
            int[] example = examples[index];
            UUID next = classifiedAsset(a(), session, "CID-ESCALA-" + index, refs);
            UUID valuation = id(startValuation(a(), next, session, completeInputs(example[0], example[1], example[2]), null)
                    .andExpect(status().isCreated()).andReturn(), "valoracionId");
            confirmValuation(a(), next, valuation, session, 0).andExpect(status().isOk())
                    .andExpect(jsonPath("$.nivel").value(levels[index]))
                    .andExpect(jsonPath("$.criticidad").value(example[3]));
        }
    }

    @Test
    void validatesConditionalDataAndKeepsConfirmedVersionsAndTheirCategories() throws Exception {
        var editor = account();
        member(editor, b(), "AE");
        var session = login(editor);
        UUID asset = classifiedAsset(b(), session, "CID-DATOS", assetReferences(b(), session));
        startValuation(b(), asset, session, Map.of("contieneDatosPersonales", false, "contieneDatosMenores", true), null)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields[0].field").value("entradas.contieneDatosMenores"));
        startValuation(b(), asset, session, Map.of("contieneDatosPersonales", true, "autorizacionTratamiento", "NO_APLICA"), null)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields[0].field").value("entradas.justificacionNoAplica"));
        startValuation(b(), asset, session, Map.of("contieneDatosPersonales", true,
                        "tiposDatosPersonales", List.of("SALUD", "SALUD")), null)
                .andExpect(status().isBadRequest());
        startValuation(b(), asset, session, Map.of("contieneDatosPersonales", true,
                        "tiposDatosPersonales", List.of("INVENTADO")), null)
                .andExpect(status().isBadRequest());

        var inputs = new HashMap<>(completeInputs(5, 3, 1));
        inputs.put("contieneDatosPersonales", true);
        UUID initial = id(startValuation(b(), asset, session, inputs, null)
                .andExpect(status().isCreated()).andReturn(), "valoracionId");
        confirmValuation(b(), asset, initial, session, 0).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CLASIFICACION_INCOMPLETA"));
        inputs.put("contieneDatosMenores", false);
        inputs.put("tiposDatosPersonales", List.of("IDENTIFICACION", "CONTACTO"));
        inputs.put("finalidadTratamiento", "Registro de prueba");
        inputs.put("autorizacionTratamiento", "NO_APLICA");
        inputs.put("justificacionNoAplica", "Justificación ficticia");
        editValuation(b(), asset, initial, session, 0, inputs).andExpect(status().isOk());
        confirmValuation(b(), asset, initial, session, 1).andExpect(status().isOk())
                .andExpect(jsonPath("$.versionTiposDatos").value("DATOS_PERSONALES_V1"))
                .andExpect(jsonPath("$.entradas.tiposDatosPersonales.length()").value(2));
        editValuation(b(), asset, initial, session, 2, completeInputs(1, 1, 1)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VALORACION_CONFIRMADA"));

        startValuation(b(), asset, session, Map.of(), null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields[0].field").value("motivoRevision"));
        UUID next = id(startValuation(b(), asset, session, inputs, "Corrección de captura")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.numero").value(2))
                .andExpect(jsonPath("$.vigente").value(false)).andReturn(), "valoracionId");
        mvc.perform(get(VALUATIONS + "/{valoracionId}", b(), asset, initial).session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.vigente").value(true));
        editValuation(b(), asset, next, session, 0, completeInputs(1, 1, 1))
                .andExpect(status().isOk()).andExpect(jsonPath("$.entradas.tiposDatosPersonales").isEmpty());
        confirmValuation(b(), asset, next, session, 1).andExpect(status().isOk())
                .andExpect(jsonPath("$.vigente").value(true)).andExpect(jsonPath("$.nivel").value("BAJA"));
        mvc.perform(get(VALUATIONS + "/{valoracionId}", b(), asset, initial).session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.vigente").value(false))
                .andExpect(jsonPath("$.entradas.tiposDatosPersonales.length()").value(2));
        mvc.perform(get(ASSETS + "/{id}", b(), asset).session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.estadoClasificacion").value("COMPLETO"))
                .andExpect(jsonPath("$.revision").value(2));

        try (var connection = fixture.database.connect(fixture.second.scope().runtimeRole(), fixture.second.runtimePassword());
                var statement = connection.createStatement()) {
            assertSqlState(() -> statement.executeUpdate("UPDATE valoraciones_activo SET fundamento = 'alterado' WHERE id = '"
                    + initial + "'"), "23514");
            assertSqlState(() -> statement.executeUpdate("DELETE FROM valoracion_tipos_dato WHERE valoracion_id = '"
                    + initial + "'"), "23514");
        }
    }

    @Test
    void classificationEnforcesPermissionsCompanyOwnershipAndArchivedState() throws Exception {
        var editor = account();
        var auditor = account();
        var responsible = account();
        member(editor, a(), "AE");
        member(editor, b(), "AE");
        member(auditor, a(), "AU");
        member(responsible, a(), "RA");
        var session = login(editor);
        var au = login(auditor);
        var ra = login(responsible);
        UUID asset = classifiedAsset(a(), session, "CID-PERM", assetReferences(a(), session));
        UUID other = classifiedAsset(b(), session, "CID-PERM", assetReferences(b(), session));
        UUID draft = id(startValuation(a(), asset, session, completeInputs(3, 1, 1), null)
                .andExpect(status().isCreated()).andReturn(), "valoracionId");
        mvc.perform(get(VALUATIONS, a(), asset).session(au)).andExpect(status().isOk());
        mvc.perform(get(VALUATIONS, a(), asset).session(ra)).andExpect(status().isForbidden());
        mvc.perform(get(VALUATIONS, b(), asset).session(session)).andExpect(status().isNotFound());
        mvc.perform(get(VALUATIONS + "/{valoracionId}", b(), other, draft).session(session))
                .andExpect(status().isNotFound());
        confirmValuation(a(), asset, draft, au, 0).andExpect(status().isForbidden());
        mvc.perform(post(VALUATIONS + "/{valoracionId}/confirmar", a(), asset, draft).session(session)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"revisionEsperada\":0}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_INVALIDO"));
        catalogPost(ASSETS + "/{id}/archivar", a(), session, Map.of("revisionEsperada", 0, "motivo", "Retiro"), asset)
                .andExpect(status().isOk());
        confirmValuation(a(), asset, draft, session, 0).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REGISTRO_ARCHIVADO"));
        mvc.perform(get(VALUATIONS + "/{valoracionId}", a(), asset, draft).session(au))
                .andExpect(status().isOk()).andExpect(jsonPath("$.estado").value("PENDIENTE"));
    }

    @Test
    void simultaneousValuationStartsAndConfirmationsSerializeByAsset() throws Exception {
        var editor = account();
        member(editor, a(), "AE");
        var session = login(editor);
        UUID asset = classifiedAsset(a(), session, "CID-RACE", assetReferences(a(), session));
        var barrier = new CyclicBarrier(2);
        var executor = Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<org.springframework.test.web.servlet.MvcResult> start = () -> {
                barrier.await(10, TimeUnit.SECONDS);
                return startValuation(a(), asset, session, completeInputs(3, 1, 1), null).andReturn();
            };
            var first = executor.submit(start);
            var second = executor.submit(start);
            var responses = List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
            assertThat(responses).extracting(result -> result.getResponse().getStatus()).containsExactlyInAnyOrder(201, 409);
            var created = responses.stream().filter(result -> result.getResponse().getStatus() == 201).findFirst().orElseThrow();
            UUID draft = id(created, "valoracionId");
            java.util.concurrent.Callable<Integer> confirm = () -> {
                barrier.await(10, TimeUnit.SECONDS);
                return confirmValuation(a(), asset, draft, session, 0).andReturn().getResponse().getStatus();
            };
            var confirmFirst = executor.submit(confirm);
            var confirmSecond = executor.submit(confirm);
            assertThat(List.of(confirmFirst.get(15, TimeUnit.SECONDS), confirmSecond.get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 409);
            assertThat(count("SELECT count(*) FROM " + fixture.first.scope().schema()
                    + ".eventos_actividad WHERE objeto_id = ? AND accion = 'CONFIRMAR_CLASIFICACION'", draft)).isOne();
        } finally {
            executor.shutdownNow();
        }
    }

    private UUID classifiedAsset(UUID company, MockHttpSession session, String code, Map<String, UUID> refs)
            throws Exception {
        return id(catalogPost(ASSETS, company, session, assetCreate(code, refs))
                .andExpect(status().isCreated()).andReturn(), "activoId");
    }

    private ResultActions startValuation(UUID company, UUID asset, MockHttpSession session, Map<String, ?> entries,
                                          String reason) throws Exception {
        var body = new HashMap<String, Object>();
        body.put("entradas", entries);
        body.put("motivoRevision", reason);
        return catalogPost(VALUATIONS, company, session, body, asset);
    }

    private ResultActions editValuation(UUID company, UUID asset, UUID valuation, MockHttpSession session,
                                         long revision, Map<String, ?> entries) throws Exception {
        return mvc.perform(put(VALUATIONS + "/{valoracionId}", company, asset, valuation).session(session).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("revisionEsperada", revision, "entradas", entries))));
    }

    private ResultActions confirmValuation(UUID company, UUID asset, UUID valuation, MockHttpSession session, long revision)
            throws Exception {
        return catalogPost(VALUATIONS + "/{valoracionId}/confirmar", company, session,
                Map.of("revisionEsperada", revision), asset, valuation);
    }

    private static Map<String, Object> completeInputs(int confidentiality, int integrity, int availability) {
        return Map.of("confidencialidad", confidentiality, "integridad", integrity, "disponibilidad", availability,
                "fundamento", "Fundamento ficticio de prueba", "contieneDatosPersonales", false);
    }

    @Test
    void commonReferenceReadsRequireAnAuthorizedOperableMembership() throws Exception {
        var aeAccount = account();
        var gsAccount = account();
        var auAccount = account();
        var raAccount = account();
        member(aeAccount, a(), "AE");
        member(gsAccount, a(), "GS");
        member(auAccount, a(), "AU");
        member(raAccount, a(), "RA");
        var ae = login(aeAccount);
        var gs = login(gsAccount);
        var au = login(auAccount);
        var ra = login(raAccount);
        mvc.perform(get(METHODOLOGIES, a())).andExpect(status().isUnauthorized());
        mvc.perform(get(CATALOGS, a()).session(ra)).andExpect(status().isForbidden());
        mvc.perform(get(METHODOLOGIES, b()).session(gs)).andExpect(status().isForbidden());
        mvc.perform(post(CATALOGS, a()).session(ae).with(csrf())).andExpect(status().isForbidden());

        for (var reader : List.of(ae, gs, au)) {
            var response = mvc.perform(get(METHODOLOGIES, a()).session(reader).param("codigo", "cid_max"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.elementos.length()").value(1))
                    .andExpect(jsonPath("$.elementos[0].identificador").value("CID_MAX_V1"))
                    .andExpect(jsonPath("$.elementos[0].reglas.valores[2]").value(5))
                    .andExpect(jsonPath("$.elementos[0].reglas.agregacion").value("MAXIMO"))
                    .andExpect(jsonPath("$.elementos[0].aprobadaEn").value("2026-11-06")).andReturn();
            var version = mapper.readTree(response.getResponse().getContentAsByteArray()).path("elementos").get(0)
                    .path("versionId").asString();
            mvc.perform(get(METHODOLOGIES + "/{versionId}", a(), version).session(reader))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.disponibleNuevos").value(true));
        }

        fixture.metadata(fixture.first, "SUSPENDIDA", "9");
        try {
            mvc.perform(get(METHODOLOGIES, a()).session(ae)).andExpect(status().isForbidden());
        } finally {
            fixture.metadata(fixture.first, "ACTIVA", "9");
        }
    }

    @Test
    void catalogPublicationKeepsOriginalReferencesAndHidesUnpublishedContent() throws Exception {
        var reader = account();
        member(reader, a(), "AU");
        var session = login(reader);
        UUID catalog = UUID.randomUUID();
        UUID oldVersion = UUID.randomUUID();
        UUID newVersion = UUID.randomUUID();
        UUID oldElement = UUID.randomUUID();
        UUID newElement = UUID.randomUUID();
        String code = "PRUEBA-" + catalog;
        sql("INSERT INTO comun.catalogos(id, codigo) VALUES (?, ?)", catalog, code);
        stageCatalog(oldVersion, catalog, "Edición 1", "1");
        stageCatalog(newVersion, catalog, "Edición 2", "2");
        stageElement(oldElement, oldVersion, "C-1", "Primera descripción");
        stageElement(newElement, newVersion, "C-1", "Descripción revisada con % literal");
        String detail = CATALOGS + "/{versionId}/elementos/{elementoId}";
        mvc.perform(get(detail, a(), newVersion, newElement).session(session)).andExpect(status().isNotFound());
        mvc.perform(get(CATALOGS, a()).session(session).param("codigo", code))
                .andExpect(status().isOk()).andExpect(jsonPath("$.elementos.length()").value(0));

        sql("UPDATE comun.versiones_catalogo SET publicada_en = clock_timestamp() WHERE id IN (?, ?)", oldVersion, newVersion);
        sql("UPDATE comun.versiones_catalogo SET retirada_en = clock_timestamp() WHERE id = ?", oldVersion);
        mvc.perform(get(CATALOGS, a()).session(session).param("codigo", code).param("tamano", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.hayMas").value(true))
                .andExpect(jsonPath("$.elementos[0].disponibleNuevos").value(false));
        mvc.perform(get(CATALOGS, a()).session(session).param("codigo", code).param("disponibleNuevos", "true"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.elementos.length()").value(1))
                .andExpect(jsonPath("$.elementos[0].versionId").value(newVersion.toString()));
        mvc.perform(get(CATALOGS + "/{versionId}/elementos", a(), newVersion).session(session)
                        .param("codigo", "c-1").param("tipo", "CONTROL").param("grupo", "TECNOLOGICO").param("texto", "%"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.elementos[0].elementoId").value(newElement.toString()))
                .andExpect(jsonPath("$.elementos[0].descripcion").doesNotExist());
        mvc.perform(get(detail, a(), oldVersion, oldElement).session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.descripcion").value("Primera descripción"))
                .andExpect(jsonPath("$.referenciaOrigen").value("Fixture!C16:F16"));
        mvc.perform(get(detail, a(), newVersion, oldElement).session(session)).andExpect(status().isNotFound());
        mvc.perform(get(detail, a(), newVersion, newElement).session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.descripcion").value("Descripción revisada con % literal"));

        assertSqlState(() -> sql("UPDATE comun.elementos_catalogo SET descripcion = 'Cambiada' WHERE id = ?", oldElement), "23514");
        assertSqlState(() -> sql("DELETE FROM comun.elementos_catalogo WHERE id = ?", oldElement), "23514");
        assertSqlState(() -> stageElement(UUID.randomUUID(), newVersion, "C-2", "Inyección"), "23514");
        assertSqlState(() -> sql("UPDATE comun.versiones_catalogo SET retirada_en = NULL WHERE id = ?", oldVersion), "23514");
        assertSqlState(() -> sql("UPDATE comun.catalogos SET codigo = 'Otro' WHERE id = ?", catalog), "23514");
    }

    @Test
    void databaseRestrictsRuntimeToPublishedReadProjections() throws Exception {
        try (var runtime = fixture.database.runtime(); var statement = runtime.createStatement()) {
            try (var rows = statement.executeQuery("SELECT identificador FROM comun.metodologias_publicadas")) {
                assertThat(rows.next()).isTrue();
            }
            assertSqlState(() -> statement.executeQuery("SELECT * FROM comun.versiones_catalogo"), "42501");
            assertSqlState(() -> statement.executeQuery("SELECT * FROM comun.elementos_catalogo"), "42501");
            assertSqlState(() -> statement.executeUpdate("UPDATE comun.versiones_metodologia SET nombre = 'Cambiada'"), "42501");
            try (var grants = statement.executeQuery("""
                    SELECT has_table_privilege('comun.metodologias_publicadas', 'INSERT,UPDATE,DELETE')
                    """)) {
                assertThat(grants.next()).isTrue();
                assertThat(grants.getBoolean(1)).isFalse();
            }
        }
        try (var tenant = fixture.database.connect(fixture.first.scope().runtimeRole(), fixture.first.runtimePassword());
                var statement = tenant.createStatement()) {
            assertSqlState(() -> statement.executeQuery("SELECT * FROM comun.catalogos_publicados"), "42501");
        }
        assertSqlState(() -> sql("UPDATE comun.versiones_metodologia SET reglas = '{}' WHERE identificador = 'CID_MAX_V1'"),
                "23514");
    }

    private void stageCatalog(UUID version, UUID catalog, String name, String label) throws SQLException {
        sql("""
                INSERT INTO comun.versiones_catalogo(id, catalogo_id, version, nombre, origen)
                VALUES (?, ?, ?, ?, 'Fixture')
                """, version, catalog, label, name);
    }

    private void stageElement(UUID element, UUID version, String code, String description) throws SQLException {
        sql("""
                INSERT INTO comun.elementos_catalogo(id, version_catalogo_id, tipo, codigo, titulo, descripcion,
                    grupo, referencia_origen)
                VALUES (?, ?, 'CONTROL', ?, 'Control de prueba', ?, 'TECNOLOGICO', 'Fixture!C16:F16')
                """, element, version, code, description);
    }

    @Test
    void evaluationUsesEqualWeightPerControlAndKeepsClauseResultsSeparate() throws Exception {
        var editor = account();
        UUID responsible = member(editor, a(), "GS");
        var session = login(editor);
        UUID method = method(a(), session, "EVALUACION_CONTROLES");
        UUID version = evaluationCatalog(
                new EvaluationItem("A", "CONTROL", "DOM_A"),
                new EvaluationItem("B1", "CONTROL", "DOM_B"),
                new EvaluationItem("B2", "CONTROL", "DOM_B"),
                new EvaluationItem("B3", "CONTROL", "DOM_B"),
                new EvaluationItem("4.1", "CLAUSULA", "CLAUSULA_4"));
        UUID evaluation = createEvaluation(a(), session, responsible, version, method);
        var responses = evaluationResponses(a(), evaluation, session);
        evaluationResults(a(), evaluation, session).andExpect(status().isOk())
                .andExpect(jsonPath("$.controlesGlobal.estado").value("PENDIENTE"))
                .andExpect(jsonPath("$.controlesGlobal.sinDecidir").value(4))
                .andExpect(jsonPath("$.controlesGlobal.puntaje").isEmpty())
                .andExpect(jsonPath("$.controlesGlobal.avance").value(0));
        setAnswer(a(), evaluation, responses.get("A"), session, 0, scored(100)).andExpect(status().isOk());
        setAnswer(a(), evaluation, responses.get("B1"), session, 1, scored(0)).andExpect(status().isOk());
        setAnswer(a(), evaluation, responses.get("B2"), session, 2, scored(0)).andExpect(status().isOk());
        setAnswer(a(), evaluation, responses.get("B3"), session, 3, scored(0)).andExpect(status().isOk());
        setAnswer(a(), evaluation, responses.get("4.1"), session, 4, scored(80)).andExpect(status().isOk());

        evaluationResults(a(), evaluation, session).andExpect(status().isOk())
                .andExpect(jsonPath("$.controlesGlobal.puntaje").value(25))
                .andExpect(jsonPath("$.controlesGlobal.avance").value(100))
                .andExpect(jsonPath("$.controlesGlobal.evaluados").value(4))
                .andExpect(jsonPath("$.dominios[0].resultado.puntaje").value(100))
                .andExpect(jsonPath("$.dominios[1].resultado.puntaje").value(0))
                .andExpect(jsonPath("$.clausulas[0].resultado.puntaje").value(80))
                .andExpect(jsonPath("$.phva").doesNotExist());

        UUID second = createEvaluation(a(), session, responsible, version, method);
        evaluationResults(a(), second, session).andExpect(status().isOk())
                .andExpect(jsonPath("$.controlesGlobal.sinDecidir").value(4))
                .andExpect(jsonPath("$.controlesGlobal.puntaje").isEmpty());
        evaluationResults(a(), evaluation, session).andExpect(status().isOk())
                .andExpect(jsonPath("$.controlesGlobal.puntaje").value(25));
    }

    @Test
    void evaluationDistinguishesZeroPendingAndJustifiedExclusion() throws Exception {
        var editor = account();
        UUID responsible = member(editor, b(), "AE");
        var session = login(editor);
        UUID version = evaluationCatalog(new EvaluationItem("C1", "CONTROL", "DOM"),
                new EvaluationItem("C2", "CONTROL", "DOM"), new EvaluationItem("C3", "CONTROL", "DOM"),
                new EvaluationItem("C4", "CONTROL", "DOM"), new EvaluationItem("5.1", "CLAUSULA", "CLAUSULA_5"));
        UUID evaluation = createEvaluation(b(), session, responsible, version, method(b(), session, "EVALUACION_CONTROLES"));
        var rows = evaluationResponses(b(), evaluation, session);
        var excluded = Map.of("aplicabilidad", "NO_APLICA", "justificacionAplicabilidad", "Fuera del alcance descrito");
        setAnswer(b(), evaluation, rows.get("5.1"), session, 0, excluded).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields[0].field").value("aplicabilidad"));
        setAnswer(b(), evaluation, rows.get("C4"), session, 0, Map.of("aplicabilidad", "NO_APLICA"))
                .andExpect(status().isBadRequest());
        setAnswer(b(), evaluation, rows.get("C4"), session, 0, scored(50)).andExpect(status().isBadRequest());
        setAnswer(b(), evaluation, rows.get("C4"), session, 0, Map.of("aplicabilidad", "APLICA", "calificacion", 0))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields[0].field").value("fundamento"));

        setAnswer(b(), evaluation, rows.get("C1"), session, 0, scored(100)).andExpect(status().isOk());
        setAnswer(b(), evaluation, rows.get("C2"), session, 1, scored(60)).andExpect(status().isOk());
        setAnswer(b(), evaluation, rows.get("C3"), session, 2, Map.of("aplicabilidad", "APLICA"))
                .andExpect(status().isOk());
        setAnswer(b(), evaluation, rows.get("C4"), session, 3, excluded).andExpect(status().isOk())
                .andExpect(jsonPath("$.respuesta.calificacion").isEmpty());
        evaluationResults(b(), evaluation, session).andExpect(status().isOk())
                .andExpect(jsonPath("$.controlesGlobal.puntaje").value(80))
                .andExpect(jsonPath("$.controlesGlobal.avance").value(66.67))
                .andExpect(jsonPath("$.controlesGlobal.pendientes").value(1))
                .andExpect(jsonPath("$.controlesGlobal.noAplican").value(1))
                .andExpect(jsonPath("$.controlesGlobal.estado").value("PROVISIONAL"));

        setAnswer(b(), evaluation, rows.get("C3"), session, 4, Map.of("aplicabilidad", "SIN_DECIDIR"))
                .andExpect(status().isOk());
        evaluationResults(b(), evaluation, session).andExpect(status().isOk())
                .andExpect(jsonPath("$.controlesGlobal.avance").value(66.67))
                .andExpect(jsonPath("$.controlesGlobal.sinDecidir").value(1))
                .andExpect(jsonPath("$.controlesGlobal.pendientes").value(0));
        for (int i = 1; i <= 3; i++) {
            setAnswer(b(), evaluation, rows.get("C" + i), session, 4 + i, excluded).andExpect(status().isOk());
        }
        evaluationResults(b(), evaluation, session).andExpect(status().isOk())
                .andExpect(jsonPath("$.controlesGlobal.estado").value("NO_APLICA"))
                .andExpect(jsonPath("$.controlesGlobal.puntaje").isEmpty())
                .andExpect(jsonPath("$.controlesGlobal.avance").isEmpty());
    }

    @Test
    void evaluationsEnforceLocalResponsibleFixedVersionsAndReadPermissions() throws Exception {
        var editor = account();
        var auditor = account();
        var ra = account();
        UUID local = member(editor, a(), "GS");
        UUID foreign = member(editor, b(), "AE");
        UUID reader = member(auditor, a(), "AU");
        member(ra, a(), "RA");
        var session = login(editor);
        var auSession = login(auditor);
        var raSession = login(ra);
        UUID method = method(a(), session, "EVALUACION_CONTROLES");
        UUID version = evaluationCatalog(new EvaluationItem("REF", "CONTROL", "DOM"));
        var path = EVALUATIONS + "/{evaluacionId}";
        catalogPost(EVALUATIONS, a(), auSession, evaluationInput(local, version, method)).andExpect(status().isForbidden());
        mvc.perform(post(EVALUATIONS, a()).session(session).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(evaluationInput(local, version, method))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_INVALIDO"));
        for (UUID invalid : List.of(foreign, reader)) {
            catalogPost(EVALUATIONS, a(), session, evaluationInput(invalid, version, method))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RESPONSABLE_NO_DISPONIBLE"));
        }
        catalogPost(EVALUATIONS, a(), session, evaluationInput(local, version, method(a(), session, "CID_MAX")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VERSION_NO_DISPONIBLE"));
        UUID evaluation = createEvaluation(a(), session, local, version, method);
        UUID response = evaluationResponses(a(), evaluation, session).get("REF");
        UUID foreignEvaluation = createEvaluation(b(), session, foreign, version, method);
        mvc.perform(get(path, a(), evaluation).session(auSession)).andExpect(status().isOk());
        mvc.perform(get(path, a(), evaluation).session(raSession)).andExpect(status().isForbidden());
        mvc.perform(get(path, a(), foreignEvaluation).session(session)).andExpect(status().isNotFound());
        setAnswer(b(), foreignEvaluation, response, session, 0, scored(100)).andExpect(status().isNotFound());

        UUID otherEdition = evaluationCatalog(new EvaluationItem("REF", "CONTROL", "DOM"));
        assertSqlState(() -> sql("""
                INSERT INTO %s.respuestas_evaluacion(id, empresa_id, evaluacion_id, version_catalogo_id,
                    elemento_id, tipo, codigo, titulo, grupo)
                SELECT ?, ?, ?, version_catalogo_id, id, tipo, codigo, titulo, grupo
                FROM comun.elementos_catalogo WHERE version_catalogo_id = ?
                """.formatted(fixture.first.scope().schema()), UUID.randomUUID(), a(), evaluation, otherEdition), "23503");
        sql("UPDATE comun.versiones_catalogo SET retirada_en = clock_timestamp() WHERE id = ?", version);
        catalogPost(EVALUATIONS, a(), session, evaluationInput(local, version, method))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VERSION_NO_DISPONIBLE"));
        setAnswer(a(), evaluation, response, session, 0, scored(100)).andExpect(status().isOk());
        mvc.perform(get(path, a(), evaluation).session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.versionCatalogoId").value(version.toString()));
    }

    @Test
    void evaluationSerializesEditsAndBindsFindingsToTheirOwnResponse() throws Exception {
        var editor = account();
        UUID responsible = member(editor, a(), "AE");
        var session = login(editor);
        UUID version = evaluationCatalog(new EvaluationItem("F1", "CONTROL", "DOM"),
                new EvaluationItem("F2", "CONTROL", "DOM"));
        UUID evaluation = createEvaluation(a(), session, responsible, version, method(a(), session, "EVALUACION_CONTROLES"));
        var rows = evaluationResponses(a(), evaluation, session);
        var barrier = new CyclicBarrier(2);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return setAnswer(a(), evaluation, rows.get("F1"), session, 0, scored(20)).andReturn().getResponse().getStatus();
            });
            var second = executor.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return setAnswer(a(), evaluation, rows.get("F2"), session, 0, scored(40)).andReturn().getResponse().getStatus();
            });
            assertThat(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 409);
        } finally {
            executor.shutdownNow();
        }
        String findings = EVALUATION_RESPONSES + "/{respuestaId}/hallazgos";
        UUID firstResponse = rows.get("F1");
        var created = catalogPost(findings, a(), session,
                Map.of("revisionEsperada", 1, "descripcionBrecha", "Brecha ficticia"), evaluation, firstResponse)
                .andExpect(status().isCreated()).andExpect(jsonPath("$.revisionEvaluacion").value(2)).andReturn();
        UUID finding = id(created, "hallazgoId");
        var update = Map.of("revisionEsperada", 2, "descripcionBrecha", "Brecha revisada", "recomendacion", "Documentar");
        mvc.perform(put(findings + "/{hallazgoId}", a(), evaluation, rows.get("F2"), finding).session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(update)))
                .andExpect(status().isNotFound());
        mvc.perform(put(findings + "/{hallazgoId}", a(), evaluation, firstResponse, finding).session(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(update)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.revisionEvaluacion").value(3));
        mvc.perform(get(findings, a(), evaluation, firstResponse).session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.elementos[0].hallazgo.recomendacion").value("Documentar"));

        String schema = fixture.first.scope().schema();
        sql("UPDATE " + schema + ".evaluaciones SET estado = 'EN_REVISION' WHERE id = ?", evaluation);
        setAnswer(a(), evaluation, firstResponse, session, 3, scored(60)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EVALUACION_NO_EDITABLE"));
        try (var connection = fixture.database.connect(fixture.first.scope().runtimeRole(), fixture.first.runtimePassword());
                var statement = connection.createStatement()) {
            assertSqlState(() -> statement.executeUpdate("UPDATE evaluaciones SET estado = 'CERRADA'"), "42501");
            assertSqlState(() -> statement.executeUpdate("UPDATE respuestas_evaluacion SET fundamento = 'alterado' WHERE id = '"
                    + firstResponse + "'"), "23514");
        }
    }

    @Test
    void evidenceUsesSessionCsrfAndFullObjectScopeForPrivateDownloads() throws Exception {
        var editor = account();
        UUID local = member(editor, a(), "GS");
        UUID foreign = member(editor, b(), "AE");
        var session = login(editor);
        var reader = account();
        UUID readerMembership = member(reader, a(), "AU");
        var au = login(reader);
        var responsible = account();
        member(responsible, a(), "RA");
        var ra = login(responsible);
        UUID catalog = evaluationCatalog(new EvaluationItem("A", "CONTROL", "DOM_A"),
                new EvaluationItem("B", "CONTROL", "DOM_A"));
        UUID evaluation = createEvaluation(a(), session, local, catalog, method(a(), session, "EVALUACION_CONTROLES"));
        var rows = evaluationResponses(a(), evaluation, session);
        UUID response = rows.get("A");
        UUID foreignEvaluation = createEvaluation(b(), session, foreign, catalog, method(b(), session, "EVALUACION_CONTROLES"));
        UUID foreignResponse = evaluationResponses(b(), foreignEvaluation, session).get("A");
        byte[] png = image("png");

        mvc.perform(evidenceUpload(a(), evaluation, response, session, 0, "soporte.png", png))
                .andExpect(status().isForbidden());
        mvc.perform(evidenceUpload(a(), evaluation, response, au, 0, "soporte.png", png).with(csrf().asHeader()))
                .andExpect(status().isForbidden());
        mvc.perform(evidenceUpload(a(), evaluation, response, session, 0, "acta técnica.PNG", png)
                        .param("descripcion", "Acta de verificación").with(csrf().asHeader()))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.revisionEvaluacion").value(1))
                .andExpect(jsonPath("$.evidencia.tipoDetectado").value("image/png"))
                .andExpect(jsonPath("$.evidencia.tamanoBytes").value(png.length))
                .andExpect(result -> assertThat(result.getResponse().getHeader("Location")).endsWith("/archivo"));
        var listing = mvc.perform(get(EVALUATION_EVIDENCE, a(), evaluation, response).session(au))
                .andExpect(status().isOk()).andExpect(jsonPath("$.elementos.length()").value(1))
                .andExpect(jsonPath("$.elementos[0].descripcion").value("Acta de verificación"))
                .andExpect(jsonPath("$.elementos[0].nombreOriginal").value("acta técnica.PNG"))
                .andExpect(jsonPath("$.elementos[0].claveAlmacenamiento").doesNotExist()).andReturn();
        UUID evidence = UUID.fromString(mapper.readTree(listing.getResponse().getContentAsByteArray()).path("elementos")
                .get(0).path("evidenciaId").asString());
        mvc.perform(get(EVIDENCE_FILE, a(), evaluation, response, evidence).session(au))
                .andExpect(status().isOk()).andExpect(content().bytes(png))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(result -> assertThat(result.getResponse().getHeader("Content-Disposition")).startsWith("attachment;"));
        mvc.perform(get(EVIDENCE_FILE, a(), evaluation, response, evidence)).andExpect(status().isUnauthorized());
        mvc.perform(get(EVIDENCE_FILE, a(), evaluation, response, evidence).session(ra)).andExpect(status().isForbidden());
        mvc.perform(get(EVIDENCE_FILE, a(), evaluation, rows.get("B"), evidence).session(session)).andExpect(status().isNotFound());
        mvc.perform(get(EVIDENCE_FILE, b(), foreignEvaluation, foreignResponse, evidence).session(session))
                .andExpect(status().isNotFound());
        mvc.perform(get(EVALUATION_EVIDENCE, b(), foreignEvaluation, foreignResponse).session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.elementos").isEmpty());
        sql("UPDATE comun.membresias SET estado = 'REVOCADA', revocada_en = CURRENT_TIMESTAMP WHERE id = ?", readerMembership);
        mvc.perform(get(EVIDENCE_FILE, a(), evaluation, response, evidence).session(au)).andExpect(status().isForbidden());

        String schema = fixture.first.scope().schema();
        assertThat(count("SELECT count(*) FROM " + schema + ".eventos_actividad WHERE objeto_id = ?"
                + " AND accion = 'CARGAR_EVIDENCIA' AND estado_nuevo = 'DISPONIBLE'", evidence)).isOne();
        try (var tenant = fixture.database.connect(fixture.second.scope().runtimeRole(), fixture.second.runtimePassword());
                var statement = tenant.createStatement()) {
            assertSqlState(() -> statement.executeQuery("SELECT * FROM " + schema + ".evidencias"), "42501");
        }
        try (var tenant = fixture.database.connect(fixture.first.scope().runtimeRole(), fixture.first.runtimePassword());
                var statement = tenant.createStatement()) {
            assertSqlState(() -> statement.executeUpdate("DELETE FROM evidencias WHERE id = '" + evidence + "'"), "42501");
            assertSqlState(() -> statement.executeUpdate("UPDATE evidencias SET estado_carga = 'FALLIDA' WHERE id = '"
                    + evidence + "'"), "23514");
        }
        sql("UPDATE " + schema + ".evaluaciones SET estado = 'EN_REVISION' WHERE id = ?", evaluation);
        mvc.perform(evidenceUpload(a(), evaluation, response, session, 1, "nuevo.png", png).with(csrf().asHeader()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EVALUACION_NO_EDITABLE"));
        mvc.perform(get(EVIDENCE_FILE, a(), evaluation, response, evidence).session(session))
                .andExpect(status().isOk()).andExpect(content().bytes(png));
    }

    @Test
    void evidenceChecksContentSizeAndRevisionAndAcceptsPdfAndJpeg() throws Exception {
        var draft = evidenceDraft(a());
        byte[] png = image("png");
        var pdf = new ByteArrayOutputStream();
        try (var document = new PDDocument()) {
            document.addPage(new PDPage());
            document.save(pdf);
        }
        mvc.perform(draft.upload(0, "imagen.png", new byte[10_000_001]).with(csrf().asHeader()))
                .andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.code").value("CARGA_DEMASIADO_GRANDE"));
        mvc.perform(draft.upload(0, "falso.pdf", png).with(csrf().asHeader()))
                .andExpect(status().isBadRequest());
        mvc.perform(draft.upload(0, "truncado.png", java.util.Arrays.copyOf(png, 15)).with(csrf().asHeader()))
                .andExpect(status().isBadRequest());
        mvc.perform(draft.upload(0, "falso.pdf", "%PDF-1.7\ntexto\n%%EOF\n".getBytes(java.nio.charset.StandardCharsets.UTF_8))
                        .with(csrf().asHeader()))
                .andExpect(status().isBadRequest());
        mvc.perform(draft.upload(0, "../imagen.png", png).with(csrf().asHeader()))
                .andExpect(status().isBadRequest());
        mvc.perform(draft.upload(0, "acta.pdf", pdf.toByteArray()).with(csrf().asHeader()))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.evidencia.tipoDetectado").value("application/pdf"));
        mvc.perform(draft.upload(0, "foto.jpeg", image("jpeg")).with(csrf().asHeader()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REVISION_DESACTUALIZADA"));
        mvc.perform(draft.upload(1, "foto.jpeg", image("jpeg")).with(csrf().asHeader()))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.evidencia.tipoDetectado").value("image/jpeg"));
        mvc.perform(get(EVALUATION_EVIDENCE, draft.company(), draft.evaluation(), draft.response()).session(draft.session())
                        .param("tamano", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.elementos.length()").value(1))
                .andExpect(jsonPath("$.hayMas").value(true));
        var unconfigured = evidenceDraft(c());
        mvc.perform(unconfigured.upload(0, "valido.png", png).with(csrf().asHeader()))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("CUOTA_EVIDENCIAS_NO_CONFIGURADA"));
    }

    @Test
    void blockedOrUnscannedUploadsNeverPublishOrConsumeQuota() throws Exception {
        var draft = evidenceDraft(a());
        String schema = fixture.first.scope().schema();
        long used = count("SELECT bytes_ocupados FROM " + schema + ".uso_evidencias");
        long before = fileEntries(evidenceVolume.root.resolve(a().toString()));
        byte[] png = image("png");
        try {
            clamd.verdict = bytes -> "stream: MSPI-Test FOUND";
            mvc.perform(draft.upload(0, "archivo.png", png).with(csrf().asHeader()))
                    .andExpect(status().is(422)).andExpect(jsonPath("$.code").value("EVIDENCIA_BLOQUEADA"))
                    .andExpect(result -> assertThat(result.getResponse().getContentAsString()).doesNotContain("MSPI-Test"));
            clamd.verdict = bytes -> null;
            mvc.perform(draft.upload(0, "archivo.png", png).with(csrf().asHeader()))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("INSPECCION_EVIDENCIAS_NO_DISPONIBLE"));
            assertThat(count("SELECT count(*) FROM " + schema + ".evidencias WHERE respuesta_id = ?", draft.response())).isZero();
            assertThat(count("SELECT bytes_ocupados FROM " + schema + ".uso_evidencias")).isEqualTo(used);
            assertThat(fileEntries(evidenceVolume.root.resolve(a().toString()))).isEqualTo(before);
        } finally {
            clamd.verdict = bytes -> "stream: OK";
        }
        mvc.perform(draft.upload(0, "archivo.png", png).with(csrf().asHeader()))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.evidencia.estadoInspeccion").value("LIMPIA"))
                .andExpect(jsonPath("$.revisionEvaluacion").value(1));
    }

    @Test
    void legacyEvidenceRequiresInspectionAndQuarantineSurvivesScannerRecovery() throws Exception {
        var draft = evidenceDraft(a());
        byte[] png = image("png");
        var published = mvc.perform(draft.upload(0, "legado.png", png).with(csrf().asHeader()))
                .andExpect(status().isCreated()).andReturn();
        UUID id = UUID.fromString(mapper.readTree(published.getResponse().getContentAsByteArray()).path("evidencia").path("evidenciaId").asString());
        String schema = fixture.first.scope().schema();
        // Represent a file migrated from V7: original bytes remain private and uninspected.
        sql("UPDATE " + schema + ".evidencias SET estado_inspeccion = 'PENDIENTE', motor_firma = NULL,"
                + " inspeccionada_en = NULL WHERE id = ?", id);
        sql("UPDATE " + schema + ".evaluaciones SET estado = 'EN_REVISION' WHERE id = ?", draft.evaluation());
        var download = get(EVIDENCE_FILE, a(), draft.evaluation(), draft.response(), id).session(draft.session());
        var list = get(EVALUATION_EVIDENCE, a(), draft.evaluation(), draft.response()).session(draft.session());
        mvc.perform(list).andExpect(status().isOk()).andExpect(jsonPath("$.elementos[0].estadoInspeccion").value("PENDIENTE"));
        try {
            clamd.verdict = bytes -> "stream: insufficient scan ERROR";
            mvc.perform(download).andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("INSPECCION_EVIDENCIAS_NO_DISPONIBLE"));
            mvc.perform(list).andExpect(status().isOk()).andExpect(jsonPath("$.elementos[0].estadoInspeccion").value("ERROR"));
            clamd.verdict = bytes -> "stream: OK";
            mvc.perform(download).andExpect(status().isOk()).andExpect(content().bytes(png));
            clamd.verdict = bytes -> "stream: MSPI-Test FOUND";
            mvc.perform(download).andExpect(status().is(422)).andExpect(jsonPath("$.code").value("EVIDENCIA_BLOQUEADA"));
        } finally {
            clamd.verdict = bytes -> "stream: OK";
        }
        int scans = clamd.scans.get();
        mvc.perform(download).andExpect(status().isNotFound());
        mvc.perform(list).andExpect(status().isOk()).andExpect(jsonPath("$.elementos").isEmpty());
        assertThat(clamd.scans.get()).isEqualTo(scans);
        assertThat(count("SELECT count(*) FROM " + schema + ".eventos_actividad WHERE objeto_id = ?"
                + " AND accion = 'INSPECCIONAR_EVIDENCIA' AND estado_nuevo = 'BLOQUEADA'", id)).isOne();
        assertThat(count("SELECT revision FROM " + schema + ".evaluaciones WHERE id = ?", draft.evaluation())).isOne();
        try (var connection = fixture.database.connect(fixture.first.scope().runtimeRole(), fixture.first.runtimePassword());
                var statement = connection.createStatement()) {
            assertSqlState(() -> statement.executeUpdate("UPDATE evidencias SET estado_inspeccion = 'LIMPIA' WHERE id = '"
                    + id + "'"), "23514");
        }
    }

    @Test
    void concurrentUploadsToDifferentEvaluationsShareOneCompanyQuota() throws Exception {
        var first = evidenceDraft(b());
        var second = evidenceDraft(b());
        String usage = fixture.second.scope().schema() + ".uso_evidencias";
        long previous = count("SELECT bytes_ocupados FROM " + usage);
        byte[] png = image("png");
        long available = EvidenceVolume.QUOTA - png.length;
        sql("UPDATE " + usage + " SET bytes_ocupados = ?", available);
        var barrier = new CyclicBarrier(2);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var firstUpload = executor.submit(() -> raceUpload(first, png, barrier));
            var secondUpload = executor.submit(() -> raceUpload(second, png, barrier));
            assertThat(List.of(firstUpload.get(15, TimeUnit.SECONDS), secondUpload.get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(201, 409);
            assertThat(count("SELECT bytes_ocupados FROM " + usage)).isEqualTo(EvidenceVolume.QUOTA);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
            long newBytes = count("SELECT bytes_ocupados FROM " + usage) - available;
            sql("UPDATE " + usage + " SET bytes_ocupados = ?", previous + newBytes);
        }
    }

    @Test
    void failedStorageLeavesTerminalAttemptWithoutAvailabilityOrQuotaConsumption() throws Exception {
        var draft = evidenceDraft(a());
        String schema = fixture.first.scope().schema();
        long usage = count("SELECT bytes_ocupados FROM " + schema + ".uso_evidencias");
        Path company = evidenceVolume.root.resolve(a().toString());
        Path saved = evidenceVolume.root.resolve("obstruccion-" + a());
        boolean existed = Files.isDirectory(company);
        if (existed) Files.move(company, saved);
        try {
            Files.writeString(company, "obstruye la creación del directorio");
            mvc.perform(draft.upload(0, "recuperable.png", image("png")).with(csrf().asHeader()))
                    .andExpect(status().isServiceUnavailable());
            assertThat(count("SELECT count(*) FROM " + schema + ".evidencias WHERE respuesta_id = ? AND estado_carga = 'FALLIDA'",
                    draft.response())).isOne();
            assertThat(count("SELECT bytes_ocupados FROM " + schema + ".uso_evidencias")).isEqualTo(usage);
            mvc.perform(get(EVALUATION_EVIDENCE, a(), draft.evaluation(), draft.response()).session(draft.session()))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.elementos").isEmpty());
        } finally {
            Files.deleteIfExists(company);
            if (existed) Files.move(saved, company);
        }
        mvc.perform(draft.upload(0, "recuperable.png", image("png")).with(csrf().asHeader()))
                .andExpect(status().isCreated());
    }

    @Test
    void rollbackAfterWritingFileCompensatesPrivateContentAndKeepsDraftRevision() throws Exception {
        var draft = evidenceDraft(a());
        String schema = fixture.first.scope().schema();
        Path company = evidenceVolume.root.resolve(a().toString());
        long before = fileEntries(company);
        long usage = count("SELECT bytes_ocupados FROM " + schema + ".uso_evidencias");
        sql("ALTER TABLE " + schema + ".eventos_actividad ADD CONSTRAINT falla_publicacion"
                + " CHECK (accion <> 'CARGAR_EVIDENCIA') NOT VALID");
        try {
            mvc.perform(draft.upload(0, "reintento.png", image("png")).with(csrf().asHeader()))
                    .andExpect(status().isInternalServerError());
            assertThat(count("SELECT count(*) FROM " + schema + ".evidencias WHERE respuesta_id = ?", draft.response()))
                    .isZero();
            assertThat(count("SELECT bytes_ocupados FROM " + schema + ".uso_evidencias")).isEqualTo(usage);
            assertThat(fileEntries(company)).isEqualTo(before);
        } finally {
            sql("ALTER TABLE " + schema + ".eventos_actividad DROP CONSTRAINT falla_publicacion");
        }
        mvc.perform(draft.upload(0, "reintento.png", image("png")).with(csrf().asHeader())).andExpect(status().isCreated());
    }

    private int raceUpload(EvidenceDraft draft, byte[] png, CyclicBarrier barrier) throws Exception {
        barrier.await(10, TimeUnit.SECONDS);
        return mvc.perform(draft.upload(0, "cuota.png", png).with(csrf().asHeader()))
                .andReturn().getResponse().getStatus();
    }

    private EvidenceDraft evidenceDraft(UUID company) throws Exception {
        var editor = account();
        UUID membership = member(editor, company, "AE");
        var session = login(editor);
        UUID catalog = evaluationCatalog(new EvaluationItem("A", "CONTROL", "DOM_A"));
        UUID evaluation = createEvaluation(company, session, membership, catalog, method(company, session, "EVALUACION_CONTROLES"));
        return new EvidenceDraft(company, evaluation, evaluationResponses(company, evaluation, session).get("A"), session);
    }

    private static MockMultipartHttpServletRequestBuilder evidenceUpload(UUID company, UUID evaluation, UUID response,
            MockHttpSession session, long revision, String name, byte[] data) {
        var request = multipart(EVALUATION_EVIDENCE, company, evaluation, response)
                .file(new MockMultipartFile("archivo", name, "application/octet-stream", data));
        request.session(session).param("revisionEsperada", Long.toString(revision));
        return request;
    }

    private record EvidenceDraft(UUID company, UUID evaluation, UUID response, MockHttpSession session) {
        MockMultipartHttpServletRequestBuilder upload(long revision, String name, byte[] bytes) {
            return evidenceUpload(company, evaluation, response, session, revision, name, bytes);
        }
    }

    private static byte[] image(String format) throws IOException {
        var output = new ByteArrayOutputStream();
        assertThat(ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), format, output)).isTrue();
        return output.toByteArray();
    }

    private static long fileEntries(Path path) throws IOException {
        if (!Files.isDirectory(path)) return 0;
        try (var files = Files.list(path)) {
            return files.count();
        }
    }

    @Test
    void closesF1OnlyAfterEvidenceReviewAndKeepsOriginalAndCorrectionIndependent() throws Exception {
        var editor = account();
        UUID responsible = member(editor, a(), "GS");
        UUID foreignResponsible = member(editor, b(), "GS");
        var session = login(editor);
        var auditor = account();
        member(auditor, a(), "AU");
        var reader = login(auditor);
        UUID catalog = f1Catalog();
        UUID evaluation = createEvaluation(a(), session, responsible, catalog, method(a(), session, "EVALUACION_CONTROLES"));
        var rows = evaluationResponses(a(), evaluation, session);
        UUID positive = rows.get("P");
        UUID clause = rows.get("4.1");

        lifecycle(a(), evaluation, reader, "enviar-revision", 0, Map.of()).andExpect(status().isForbidden());
        mvc.perform(post(EVALUATIONS + "/{evaluacionId}/enviar-revision", a(), evaluation).session(session)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"revisionEsperada\":0}"))
                .andExpect(status().isForbidden());
        lifecycle(a(), evaluation, session, "enviar-revision", 0, Map.of()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.fields[0].code").value("APLICABILIDAD_PENDIENTE"));
        lifecycle(a(), evaluation, session, "cerrar", 0, Map.of()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ESTADO_EVALUACION_INCOMPATIBLE"));
        setAnswer(a(), evaluation, positive, session, 0, scored(20)).andExpect(status().isOk());
        setAnswer(a(), evaluation, clause, session, 1, scored(0)).andExpect(status().isOk());
        setAnswer(a(), evaluation, rows.get("N"), session, 2,
                Map.of("aplicabilidad", "NO_APLICA", "justificacionAplicabilidad", "Fuera del alcance justificado"))
                .andExpect(status().isOk());
        lifecycle(a(), evaluation, session, "enviar-revision", 3, Map.of()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.fields[0].code").value("EVIDENCIA_REQUERIDA"));
        byte[] png = image("png");
        var uploaded = mvc.perform(evidenceUpload(a(), evaluation, positive, session, 3, "prueba.png", png)
                .with(csrf().asHeader())).andExpect(status().isCreated()).andReturn();
        UUID evidence = UUID.fromString(mapper.readTree(uploaded.getResponse().getContentAsByteArray())
                .path("evidencia").path("evidenciaId").asString());
        var proofs = Map.of("evidencias", Map.of(positive.toString(), evidence.toString()));
        lifecycle(a(), evaluation, session, "enviar-revision", 4,
                Map.of("evidencias", Map.of(clause.toString(), evidence.toString()))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.fields[?(@.code == 'EVIDENCIA_NO_VALIDA')]").isNotEmpty());
        lifecycle(a(), evaluation, session, "enviar-revision", 4, proofs).andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("EN_REVISION")).andExpect(jsonPath("$.revision").value(5));
        setAnswer(a(), evaluation, positive, session, 5, scored(80)).andExpect(status().isConflict());
        lifecycle(a(), evaluation, session, "devolver", 5, Map.of("motivo", " ")).andExpect(status().isBadRequest());
        lifecycle(a(), evaluation, session, "devolver", 5, Map.of("motivo", "Ampliar el fundamento"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.estado").value("BORRADOR"));
        lifecycle(a(), evaluation, session, "enviar-revision", 6, proofs).andExpect(status().isOk());

        Path file = evidenceVolume.root.resolve(a().toString()).resolve(evidence.toString()).resolve("contenido");
        byte[] corrupt = png.clone();
        corrupt[corrupt.length - 1] ^= 1;
        Files.write(file, corrupt);
        try {
            lifecycle(a(), evaluation, session, "cerrar", 7, proofs).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.fields[0].code").value("EVIDENCIA_NO_VALIDA"));
        } finally {
            Files.write(file, png);
        }
        lifecycle(a(), evaluation, session, "cerrar", 7, proofs).andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("CERRADA")).andExpect(jsonPath("$.revision").value(8))
                .andExpect(jsonPath("$.cerradaPor").value(editor.id().toString()));
        String closed = mvc.perform(get(EVALUATIONS + "/{evaluacionId}/cierre", a(), evaluation).session(reader))
                .andExpect(status().isOk()).andExpect(jsonPath("$.instantanea.politica").value("F1-1_EVIDENCIA_V1"))
                .andExpect(jsonPath("$.instantanea.resultados.controlesGlobal.puntaje").value(20))
                .andExpect(jsonPath("$.instantanea.evidencias['" + positive + "'].evidenciaId").value(evidence.toString()))
                .andReturn().getResponse().getContentAsString();
        evaluationResults(a(), evaluation, session).andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(8)).andExpect(jsonPath("$.controlesGlobal.puntaje").value(20));
        mvc.perform(get(EVALUATIONS + "/{evaluacionId}/cierre", b(), evaluation).session(session))
                .andExpect(status().isNotFound());
        setAnswer(a(), evaluation, positive, session, 8, scored(80)).andExpect(status().isConflict());
        String schema = fixture.first.scope().schema();
        assertSqlState(() -> sql("UPDATE " + schema + ".evaluaciones SET revision = 9 WHERE id = ?", evaluation), "23514");
        assertThat(count("SELECT count(*) FROM " + schema + ".eventos_actividad WHERE objeto_id = ?"
                + " AND accion = 'DEVOLVER' AND motivo = 'Ampliar el fundamento'", evaluation)).isOne();

        lifecycle(a(), evaluation, session, "correcciones", 8, Map.of("motivo", "Rectificación",
                "responsableId", foreignResponsible)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESPONSABLE_NO_DISPONIBLE"));
        var correctionInput = Map.of("motivo", "Rectificación", "responsableId", responsible);
        UUID correction = id(lifecycle(a(), evaluation, session, "correcciones", 8, correctionInput)
                .andExpect(status().isCreated()).andExpect(jsonPath("$.evaluacionAnteriorId").value(evaluation.toString()))
                .andExpect(jsonPath("$.versionCatalogoId").value(catalog.toString())).andReturn(), "evaluacionId");
        lifecycle(a(), evaluation, session, "correcciones", 8, correctionInput).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CORRECCION_EXISTENTE"));
        var inherited = evaluationResponses(a(), correction, session);
        var reused = Map.of("evidencias", Map.of(inherited.get("P").toString(), evidence.toString()));
        lifecycle(a(), correction, session, "enviar-revision", 0, reused).andExpect(status().isConflict())
                .andExpect(jsonPath("$.fields[?(@.code == 'RESPUESTA_HEREDADA_PENDIENTE')]")
                        .value(org.hamcrest.Matchers.hasSize(3)));
        confirmInherited(a(), correction, inherited.get("P"), session, 0).andExpect(status().isOk())
                .andExpect(jsonPath("$.respuestaAnteriorId").value(positive.toString()))
                .andExpect(jsonPath("$.heredadaConfirmada").value(true));
        confirmInherited(a(), correction, inherited.get("N"), session, 1).andExpect(status().isOk());
        setAnswer(a(), correction, inherited.get("4.1"), session, 2, scored(0)).andExpect(status().isOk())
                .andExpect(jsonPath("$.heredadaConfirmada").value(true));
        lifecycle(a(), correction, session, "enviar-revision", 3, reused).andExpect(status().isOk());
        lifecycle(a(), correction, session, "cerrar", 4, reused).andExpect(status().isOk());
        mvc.perform(get(EVALUATIONS + "/{evaluacionId}/cierre", a(), evaluation).session(reader))
                .andExpect(status().isOk()).andExpect(content().json(closed));
    }

    @Test
    void evaluationClosureRequiresPublishedPolicyAndAllowsOnlyOneConcurrentDecision() throws Exception {
        var editor = account();
        UUID responsible = member(editor, b(), "AE");
        var session = login(editor);
        UUID method = method(b(), session, "EVALUACION_CONTROLES");
        UUID other = createEvaluation(b(), session, responsible,
                evaluationCatalog(new EvaluationItem("X", "CONTROL", "DOM_A")), method);
        lifecycle(b(), other, session, "enviar-revision", 0, Map.of()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("POLITICA_EVALUACION_NO_DISPONIBLE"));

        UUID evaluation = createEvaluation(b(), session, responsible, f1Catalog(), method);
        var rows = evaluationResponses(b(), evaluation, session);
        setAnswer(b(), evaluation, rows.get("P"), session, 0, scored(0)).andExpect(status().isOk());
        setAnswer(b(), evaluation, rows.get("N"), session, 1, scored(0)).andExpect(status().isOk());
        setAnswer(b(), evaluation, rows.get("4.1"), session, 2, scored(0)).andExpect(status().isOk());
        lifecycle(b(), evaluation, session, "enviar-revision", 3, Map.of()).andExpect(status().isOk());
        var barrier = new CyclicBarrier(2);
        var executor = Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<Integer> close = () -> {
                barrier.await(10, TimeUnit.SECONDS);
                return lifecycle(b(), evaluation, session, "cerrar", 4, Map.of()).andReturn().getResponse().getStatus();
            };
            var first = executor.submit(close);
            var second = executor.submit(close);
            assertThat(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 409);
            assertThat(count("SELECT count(*) FROM " + fixture.second.scope().schema()
                    + ".eventos_actividad WHERE objeto_id = ? AND accion = 'CERRAR'", evaluation)).isOne();
        } finally {
            executor.shutdownNow();
        }
    }

    private ResultActions lifecycle(UUID company, UUID evaluation, MockHttpSession session, String command,
                                    long revision, Map<String, ?> fields) throws Exception {
        var input = new HashMap<String, Object>(fields);
        input.put("revisionEsperada", revision);
        return catalogPost(EVALUATIONS + "/{evaluacionId}/" + command, company, session, input, evaluation);
    }

    private ResultActions confirmInherited(UUID company, UUID evaluation, UUID response, MockHttpSession session,
                                            long revision) throws Exception {
        return catalogPost(EVALUATION_RESPONSES + "/{respuestaId}/confirmar-heredada", company, session,
                Map.of("revisionEsperada", revision), evaluation, response);
    }

    @Test
    void implementsAndVerifiesControlWithHistoricalVersionsAndCompanyIsolation() throws Exception {
        var editor = account();
        member(editor, a(), "GS");
        member(editor, b(), "GS");
        var session = login(editor, "192.0.2.19");
        var auditor = account();
        member(auditor, a(), "AU");
        var reader = login(auditor, "192.0.2.19");
        UUID edition = evaluationCatalog(new EvaluationItem("C", "CONTROL", "DOM"),
                new EvaluationItem("4.1", "CLAUSULA", "CLAUSULA_4"));
        UUID element = catalogElement(a(), session, edition, "C");
        UUID clause = catalogElement(a(), session, edition, "4.1");
        var detail = controlDetail(a(), session);
        var foreign = controlDetail(b(), session);
        catalogPost(CONTROL_IMPLEMENTATIONS, a(), session,
                Map.of("versionCatalogoId", edition, "elementoId", clause, "detalle", detail))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONTROL_CATALOGO_NO_DISPONIBLE"));
        catalogPost(CONTROL_IMPLEMENTATIONS, a(), session,
                Map.of("versionCatalogoId", edition, "elementoId", element, "detalle", foreign))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RESPONSABLE_NO_DISPONIBLE"));
        var created = catalogPost(CONTROL_IMPLEMENTATIONS, a(), session,
                Map.of("versionCatalogoId", edition, "elementoId", element, "detalle", detail))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.estado").value("PROPUESTO"))
                .andExpect(jsonPath("$.resultadoVigente").value("SIN_VERIFICAR")).andReturn();
        UUID control = id(created, "implementacionId");
        UUID version = nestedId(created, "versionActual", "versionId");
        mvc.perform(get(CONTROL_IMPLEMENTATIONS, a()).session(reader))
                .andExpect(status().isOk()).andExpect(jsonPath("$.elementos[0].versionActual.versionId").value(version.toString()));
        mvc.perform(get(CONTROL_IMPLEMENTATION, b(), control).session(session)).andExpect(status().isNotFound());
        catalogPost(CONTROL_IMPLEMENTATION + "/iniciar", a(), reader, Map.of("revisionEsperada", 0), control)
                .andExpect(status().isForbidden());
        mvc.perform(post(CONTROL_IMPLEMENTATION + "/iniciar", a(), control).session(session)
                .contentType(MediaType.APPLICATION_JSON).content("{\"revisionEsperada\":0}"))
                .andExpect(status().isForbidden());
        catalogPost(CONTROL_IMPLEMENTATION + "/iniciar", a(), session, Map.of("revisionEsperada", 0), control)
                .andExpect(status().isOk()).andExpect(jsonPath("$.estado").value("EN_IMPLEMENTACION"));
        UUID application = uploadControlEvidence(a(), control, version, session, 1);
        var apply = Map.of("revisionEsperada", 2, "fechaAplicacion", "2026-01-01", "evidenciaId", application);
        catalogPost(CONTROL_IMPLEMENTATION + "/confirmar-aplicacion", a(), session, apply, control)
                .andExpect(status().isOk()).andExpect(jsonPath("$.estado").value("IMPLEMENTADO"))
                .andExpect(jsonPath("$.resultadoVigente").value("SIN_VERIFICAR"));

        UUID proof = uploadControlEvidence(a(), control, version, session, 3);
        var test = controlTest(4, proof, 2, 1, "2099-12-31");
        catalogPost(CONTROL_IMPLEMENTATION + "/verificar", a(), session, test, control)
                .andExpect(status().isCreated()).andExpect(jsonPath("$.resultado").value("PARCIALMENTE_EFECTIVO"))
                .andExpect(jsonPath("$.revisionImplementacion").value(5));
        mvc.perform(get(CONTROL_IMPLEMENTATION, a(), control).session(reader))
                .andExpect(status().isOk()).andExpect(jsonPath("$.resultadoVigente").value("PARCIALMENTE_EFECTIVO"));
        catalogPost(CONTROL_IMPLEMENTATION + "/verificar", a(), session, controlTest(5, proof, 2, 2, "2099-12-31"), control)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EVIDENCIA_CONTROL_YA_VERIFICADA"));

        String schema = fixture.first.scope().schema();
        // Move the fixture's review date to the past to test effective reads without changing the host clock.
        sql("UPDATE " + schema + ".verificaciones_control SET fecha_verificacion = '2026-01-01',"
                + " proxima_revision = '2026-01-02' WHERE evidencia_id = ?", proof);
        mvc.perform(get(CONTROL_IMPLEMENTATION, a(), control).session(reader))
                .andExpect(status().isOk()).andExpect(jsonPath("$.resultadoVigente").value("SIN_VERIFICAR"))
                .andExpect(jsonPath("$.causaSinVigencia").value("VENCIDA"));

        catalogPost(CONTROL_IMPLEMENTATION + "/cambiar", a(), session,
                Map.of("revisionEsperada", 5, "detalle", detail, "motivo", "Cambió el alcance técnico"), control)
                .andExpect(status().isOk()).andExpect(jsonPath("$.estado").value("EN_IMPLEMENTACION"))
                .andExpect(jsonPath("$.versionActual.numero").value(2))
                .andExpect(jsonPath("$.resultadoVigente").value("SIN_VERIFICAR"));
        mvc.perform(get(CONTROL_VERSION + "/verificaciones", a(), control, version).session(reader))
                .andExpect(status().isOk()).andExpect(jsonPath("$.elementos[0].resultado").value("PARCIALMENTE_EFECTIVO"));
        mvc.perform(get(CONTROL_EVIDENCE + "/{evidenciaId}/archivo", a(), control, version, proof).session(reader))
                .andExpect(status().isOk()).andExpect(content().bytes(image("png")));
        mvc.perform(controlEvidenceUpload(a(), control, version, session, 6)).andExpect(status().isConflict());
        try (var connection = fixture.database.connect(fixture.first.scope().runtimeRole(), fixture.first.runtimePassword());
                var statement = connection.createStatement()) {
            assertSqlState(() -> statement.executeUpdate("UPDATE verificaciones_control SET fundamento = 'alterado'"), "42501");
        }
    }

    @Test
    void controlVerificationRejectsWrongEvidenceAndLosesValidityWhenQuarantined() throws Exception {
        var editor = account();
        member(editor, b(), "AE");
        var session = login(editor, "192.0.2.19");
        UUID edition = evaluationCatalog(new EvaluationItem("B", "CONTROL", "DOM"));
        var detail = controlDetail(b(), session);
        var created = catalogPost(CONTROL_IMPLEMENTATIONS, b(), session, Map.of("versionCatalogoId", edition,
                "elementoId", catalogElement(b(), session, edition, "B"), "detalle", detail))
                .andExpect(status().isCreated()).andReturn();
        UUID control = id(created, "implementacionId");
        UUID version = nestedId(created, "versionActual", "versionId");
        catalogPost(CONTROL_IMPLEMENTATION + "/iniciar", b(), session, Map.of("revisionEsperada", 0), control)
                .andExpect(status().isOk());
        catalogPost(CONTROL_IMPLEMENTATION + "/confirmar-aplicacion", b(), session,
                Map.of("revisionEsperada", 1, "fechaAplicacion", "2026-01-01", "evidenciaId", UUID.randomUUID()), control)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EVIDENCIA_CONTROL_NO_DISPONIBLE"));
        UUID applied = uploadControlEvidence(b(), control, version, session, 1);
        catalogPost(CONTROL_IMPLEMENTATION + "/confirmar-aplicacion", b(), session,
                Map.of("revisionEsperada", 2, "fechaAplicacion", "2026-01-01", "evidenciaId", applied), control)
                .andExpect(status().isOk());
        UUID proof = uploadControlEvidence(b(), control, version, session, 3);
        catalogPost(CONTROL_IMPLEMENTATION + "/verificar", b(), session, controlTest(4, proof, 1, 2, "2099-12-31"), control)
                .andExpect(status().isBadRequest());
        catalogPost(CONTROL_IMPLEMENTATION + "/verificar", b(), session, controlTest(4, proof, 1, 0, "2026-01-01"), control)
                .andExpect(status().isBadRequest());
        catalogPost(CONTROL_IMPLEMENTATION + "/verificar", b(), session, controlTest(4, proof, 1, 0, "2099-12-31"), control)
                .andExpect(status().isCreated()).andExpect(jsonPath("$.resultado").value("NO_EFECTIVO"));
        try {
            clamd.verdict = bytes -> "stream: MSPI-Test FOUND";
            mvc.perform(get(CONTROL_EVIDENCE + "/{evidenciaId}/archivo", b(), control, version, proof).session(session))
                    .andExpect(status().is(422));
        } finally {
            clamd.verdict = bytes -> "stream: OK";
        }
        mvc.perform(get(CONTROL_IMPLEMENTATION, b(), control).session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.resultadoVigente").value("SIN_VERIFICAR"))
                .andExpect(jsonPath("$.causaSinVigencia").value("EVIDENCIA_BLOQUEADA"));
        mvc.perform(get(CONTROL_VERSION + "/verificaciones", b(), control, version).session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.elementos[0].resultado").value("NO_EFECTIVO"));
    }

    private UUID catalogElement(UUID company, MockHttpSession session, UUID edition, String code) throws Exception {
        var listing = mvc.perform(get(CATALOGS + "/{versionId}/elementos", company, edition).session(session).param("codigo", code))
                .andExpect(status().isOk()).andReturn();
        return UUID.fromString(mapper.readTree(listing.getResponse().getContentAsByteArray()).path("elementos")
                .get(0).path("elementoId").asString());
    }

    private Map<String, Object> controlDetail(UUID company, MockHttpSession session) throws Exception {
        UUID responsible = id(catalogPost(BUSINESS_RESPONSIBLES, company, session, Map.of("nombre", "Dueño de control", "tipo", "CARGO"))
                .andExpect(status().isCreated()).andReturn(), "responsableId");
        return Map.of("nombre", "Control operativo", "descripcion", "Descripción de la aplicación",
                "alcance", "Área de prueba", "trabajo", "Revisión periódica", "responsableId", responsible, "tipo", "DETECTIVO",
                "modo", "MANUAL");
    }

    private UUID uploadControlEvidence(UUID company, UUID control, UUID version, MockHttpSession session, long revision)
            throws Exception {
        return nestedId(mvc.perform(controlEvidenceUpload(company, control, version, session, revision))
                .andExpect(status().isCreated()).andReturn(), "evidencia", "evidenciaId");
    }

    private static MockMultipartHttpServletRequestBuilder controlEvidenceUpload(UUID company, UUID control, UUID version,
            MockHttpSession session, long revision) throws IOException {
        var request = multipart(CONTROL_EVIDENCE, company, control, version)
                .file(new MockMultipartFile("archivo", "control.png", "image/png", image("png")));
        request.session(session).param("revisionEsperada", Long.toString(revision)).with(csrf().asHeader());
        return request;
    }

    private UUID nestedId(org.springframework.test.web.servlet.MvcResult result, String object, String field) throws Exception {
        return UUID.fromString(mapper.readTree(result.getResponse().getContentAsByteArray()).path(object).path(field).asString());
    }

    private static Map<String, Object> controlTest(long revision, UUID evidence, int total, int met, String due) {
        return Map.of("revisionEsperada", revision, "prueba", "Se revisan los criterios",
                "fundamento", "Acta de prueba de control", "criteriosEvaluados", total, "criteriosCumplidos", met,
                "proximaRevision", due, "evidenciaId", evidence);
    }

    private UUID f1Catalog() throws SQLException {
        UUID catalog = UUID.fromString("18f10000-0000-4000-8000-000000000001");
        UUID version = UUID.fromString("18f10000-0000-4000-8000-000000000002");
        if (count("SELECT count(*) FROM comun.versiones_catalogo WHERE id = ?", version) == 0) {
            publishEvaluationCatalog(catalog, version, "MSPI_DIAGNOSTICO_F1", "F1-1",
                    new EvaluationItem("P", "CONTROL", "DOM_A"),
                    new EvaluationItem("N", "CONTROL", "DOM_A"),
                    new EvaluationItem("4.1", "CLAUSULA", "CLAUSULA_4"));
        }
        return version;
    }

    private UUID evaluationCatalog(EvaluationItem... items) throws SQLException {
        UUID catalog = UUID.randomUUID();
        UUID version = UUID.randomUUID();
        publishEvaluationCatalog(catalog, version, "EVALUACION-" + catalog, "1", items);
        return version;
    }

    private void publishEvaluationCatalog(UUID catalog, UUID version, String code, String label, EvaluationItem... items)
            throws SQLException {
        sql("INSERT INTO comun.catalogos(id, codigo) VALUES (?, ?)", catalog, code);
        stageCatalog(version, catalog, "Edición de prueba", label);
        for (var item : items) {
            sql("""
                    INSERT INTO comun.elementos_catalogo(id, version_catalogo_id, tipo, codigo, titulo, descripcion,
                        grupo, referencia_origen)
                    VALUES (?, ?, ?, ?, 'Elemento de prueba', 'Descripción de prueba', ?, 'Fixture')
                    """, UUID.randomUUID(), version, item.type(), item.code(), item.group());
        }
        sql("UPDATE comun.versiones_catalogo SET publicada_en = clock_timestamp() WHERE id = ?", version);
    }

    private UUID method(UUID company, MockHttpSession session, String code) throws Exception {
        var result = mvc.perform(get(METHODOLOGIES, company).session(session).param("codigo", code))
                .andExpect(status().isOk()).andReturn();
        return UUID.fromString(mapper.readTree(result.getResponse().getContentAsByteArray()).path("elementos")
                .get(0).path("versionId").asString());
    }

    private UUID createEvaluation(UUID company, MockHttpSession session, UUID responsible, UUID catalog, UUID method)
            throws Exception {
        return id(catalogPost(EVALUATIONS, company, session, evaluationInput(responsible, catalog, method))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.estado").value("BORRADOR"))
                .andReturn(), "evaluacionId");
    }

    private static Map<String, Object> evaluationInput(UUID responsible, UUID catalog, UUID method) {
        return Map.of("nombre", "Diagnóstico ficticio", "fechaEvaluacion", "2026-11-07", "alcance", "Área de prueba",
                "responsableId", responsible, "versionCatalogoId", catalog, "versionMetodologiaId", method);
    }

    private Map<String, UUID> evaluationResponses(UUID company, UUID evaluation, MockHttpSession session) throws Exception {
        var result = mvc.perform(get(EVALUATION_RESPONSES, company, evaluation).session(session).param("tamano", "100"))
                .andExpect(status().isOk()).andReturn();
        var rows = new HashMap<String, UUID>();
        mapper.readTree(result.getResponse().getContentAsByteArray()).path("elementos").forEach(row ->
                rows.put(row.path("codigo").asString(), UUID.fromString(row.path("respuestaId").asString())));
        return rows;
    }

    private ResultActions setAnswer(UUID company, UUID evaluation, UUID response, MockHttpSession session,
                                    long revision, Map<String, ?> answer) throws Exception {
        var input = new HashMap<String, Object>(answer);
        input.put("revisionEsperada", revision);
        return mvc.perform(put(EVALUATION_RESPONSES + "/{respuestaId}", company, evaluation, response)
                .session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(input)));
    }

    private ResultActions evaluationResults(UUID company, UUID evaluation, MockHttpSession session) throws Exception {
        return mvc.perform(get(EVALUATIONS + "/{evaluacionId}/resultados", company, evaluation).session(session));
    }

    private static Map<String, Object> scored(int score) {
        return Map.of("aplicabilidad", "APLICA", "calificacion", score, "fundamento", "Fundamento ficticio");
    }

    record EvaluationItem(String code, String type, String group) {}

    private Map<String, UUID> assetReferences(UUID company, MockHttpSession session) throws Exception {
        var process = catalogPost(PROCESSES, company, session, Map.of("nombre", "Proceso de inventario"))
                .andExpect(status().isCreated()).andReturn();
        var owner = catalogPost(BUSINESS_RESPONSIBLES, company, session, Map.of("nombre", "Custodia", "tipo", "CARGO"))
                .andExpect(status().isCreated()).andReturn();
        return Map.of("procesoId", id(process, "procesoId"), "propietarioId", id(owner, "responsableId"));
    }

    private static Map<String, Object> assetCreate(String code, Map<String, UUID> references) {
        var body = new HashMap<>(assetEdit(0, "Equipo de red", references));
        body.remove("revisionEsperada");
        body.put("codigo", code);
        return body;
    }

    private static Map<String, Object> assetEdit(long revision, String name, Map<String, UUID> references) {
        var body = new HashMap<String, Object>(references);
        body.put("revisionEsperada", revision);
        body.put("nombre", name);
        body.put("descripcion", "Equipo de prueba");
        body.put("tipo", "RED");
        return body;
    }

    private UUID a() { return fixture.first.scope().companyId(); }
    private UUID b() { return fixture.second.scope().companyId(); }
    private UUID c() { return fixture.third.scope().companyId(); }

    private ResultActions read(MockHttpSession session, UUID company, UUID id) throws Exception {
        return mvc.perform(get(PROBE + "/registros/{id}", company, id).session(session));
    }

    private ResultActions edit(MockHttpSession session, UUID company, String value) throws Exception {
        return mvc.perform(put(PROBE + "/registros/{id}", company, SHARED).session(session).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of("valor", value))));
    }

    private Account account() throws SQLException {
        UUID id = UUID.randomUUID();
        String email = id + "@example.test";
        String password = UUID.randomUUID().toString();
        sql("""
                INSERT INTO comun.usuarios(id, nombre_visible, correo, clave_correo, correo_verificado_en,
                                           estado_cuenta, hash_credencial)
                VALUES (?, 'Persona ficticia', ?, ?, CURRENT_TIMESTAMP, 'HABILITADA', ?)
                """, id, email, email, encoder.encode(password));
        return new Account(id, email, password);
    }

    private MockHttpSession login(Account account) throws Exception {
        return login(account, "127.0.0.1");
    }

    private MockHttpSession login(Account account, String address) throws Exception {
        var result = mvc.perform(post("/api/v1/identidad/sesion").with(csrf()).with(request -> {
                    request.setRemoteAddr(address);
                    return request;
                }).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("correo", account.email(), "contrasena", account.password()))))
                .andExpect(status().isOk()).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private UUID member(Account account, UUID company, String... roles) throws SQLException {
        UUID id = UUID.randomUUID();
        try (var connection = fixture.database.admin();
                var membership = connection.prepareStatement("""
                        INSERT INTO comun.membresias (id, empresa_id, usuario_id, estado, rol_ancla)
                        VALUES (?, ?, ?, 'ACTIVA', ?)
                        """);
                var grant = connection.prepareStatement("INSERT INTO comun.membresia_roles VALUES (?, ?)")) {
            connection.setAutoCommit(false);
            membership.setObject(1, id);
            membership.setObject(2, company);
            membership.setObject(3, account.id());
            membership.setString(4, roles.length == 0 ? null : roles[0]);
            membership.executeUpdate();
            for (String role : roles) {
                grant.setObject(1, id);
                grant.setString(2, role);
                grant.executeUpdate();
            }
            connection.commit();
        }
        return id;
    }

    private void sql(String sql, Object... values) throws SQLException {
        try (var connection = fixture.database.admin(); var statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) {
                statement.setObject(index + 1, values[index]);
            }
            statement.executeUpdate();
        }
    }

    private static void assertSqlState(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, String state) {
        assertThatThrownBy(action)
                .isInstanceOfSatisfying(SQLException.class, failure -> assertThat(failure.getSQLState()).isEqualTo(state));
    }

    record Account(UUID id, String email, String password) {}

    static class TestDelivery {
        final AtomicReference<UUID> responsible = new AtomicReference<>();
    }

    static class EvidenceVolume implements AutoCloseable {
        static final long QUOTA = 50_000;
        final Path root = Files.createTempDirectory("mspi-evidencias-pruebas-");

        EvidenceVolume() throws IOException {}

        @Override
        public void close() throws IOException {
            FileSystemUtils.deleteRecursively(root);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Fixtures {
        @Bean TenantIsolationFixture probeData(TestDatabase database) throws Exception {
            return new TenantIsolationFixture(database);
        }
        @Bean @Primary TenantSecretSource probeSecrets(TenantIsolationFixture data) { return data.secrets(); }
        @Bean TestDelivery testDelivery() { return new TestDelivery(); }
        @Bean EvidenceVolume evidenceVolume() throws IOException { return new EvidenceVolume(); }
        @Bean FakeClamd clamd() throws IOException { return new FakeClamd(); }
        @Bean DynamicPropertyRegistrar evidenceProperties(TenantIsolationFixture data, EvidenceVolume volume, FakeClamd clamd) {
            return registry -> {
                registry.add("mspi.evidence.directory", () -> volume.root.toString());
                registry.add("mspi.evidence.antimalware.host", () -> "127.0.0.1");
                registry.add("mspi.evidence.antimalware.port", clamd::port);
                registry.add("mspi.evidence.quotas[" + data.first.scope().companyId() + "]", () -> EvidenceVolume.QUOTA);
                registry.add("mspi.evidence.quotas[" + data.second.scope().companyId() + "]", () -> EvidenceVolume.QUOTA);
            };
        }
    }

    @TestComponent
    @RestController
    static class ProbeController {
        private final TenantUnitOfWork work;
        private final CompanyAccess access;
        private final TestDelivery delivery;
        @PersistenceContext(unitName = "enterprise") EntityManager entityManager;

        ProbeController(TenantUnitOfWork work, CompanyAccess access, TestDelivery delivery) {
            this.work = work;
            this.access = access;
            this.delivery = delivery;
        }

        @GetMapping(PROBE + "/registros/{id}")
        Map<String, String> read(@PathVariable UUID empresaId, @PathVariable UUID id,
                                 @AuthenticationPrincipal SessionIdentity actor) {
            return work.execute(actor.usuarioId(), empresaId, CompanyPermission.READ_ASSETS,
                    () -> Map.of("valor", find(id).getValue()));
        }

        @PutMapping(PROBE + "/registros/{id}")
        @ResponseStatus(HttpStatus.NO_CONTENT)
        void edit(@PathVariable UUID empresaId, @PathVariable UUID id, @RequestBody Edit input,
                  @AuthenticationPrincipal SessionIdentity actor) {
            work.execute(actor.usuarioId(), empresaId, CompanyPermission.EDIT_ASSETS, () -> {
                find(id).setValue(input.valor());
                return null;
            });
        }

        @GetMapping(PROBE + "/actor/{actorId}")
        String forged(@PathVariable UUID empresaId, @PathVariable UUID actorId) {
            return work.execute(actorId, empresaId, CompanyPermission.READ_ASSETS, () -> "forbidden");
        }

        @GetMapping(PROBE + "/entrega")
        Map<String, String> delivery(@PathVariable UUID empresaId, @AuthenticationPrincipal SessionIdentity actor) {
            return work.execute(actor.usuarioId(), empresaId, CompanyPermission.READ_ACTIONS,
                    () -> Map.of("estado", "EN_REVISION"));
        }

        @PostMapping({PROBE + "/entrega/aprobar", PROBE + "/entrega/devolver"})
        @ResponseStatus(HttpStatus.NO_CONTENT)
        void review(@PathVariable UUID empresaId, @AuthenticationPrincipal SessionIdentity actor) {
            work.execute(actor.usuarioId(), empresaId, CompanyPermission.REVIEW_ACTION, () -> {
                access.requireIndependentReviewer(empresaId, delivery.responsible.get());
                return null;
            });
        }

        private IsolationProbe find(UUID id) {
            var probe = entityManager.find(IsolationProbe.class, id);
            if (probe == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            }
            return probe;
        }

        record Edit(String valor) {}
    }
}
