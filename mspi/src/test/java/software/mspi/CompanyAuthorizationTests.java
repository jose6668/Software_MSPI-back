package software.mspi;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
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
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"mspi.tenancy.enabled=true", "mspi.tenancy.max-pools=2",
        "mspi.tenancy.connections-per-pool=2", "mspi.tenancy.connection-timeout=5s", "mspi.tenancy.idle-ttl=30s",
        "mspi.identity.attempts-per-address=60"})
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
    private static final String BUSINESS_RESPONSIBLES = "/api/v1/empresas/{empresaId}/responsables-negocio";
    private static final String PROBE = "/api/v1/pruebas-autorizacion/{empresaId}";
    private static final UUID SHARED = TenantIsolationFixture.SHARED_ID;

    @Autowired MockMvc mvc;
    @Autowired JsonMapper mapper;
    @Autowired PasswordEncoder encoder;
    @Autowired TenantIsolationFixture fixture;
    @Autowired TestDelivery delivery;

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
            fixture.metadata(fixture.third, "ACTIVA", "3");
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

        fixture.metadata(fixture.first, "SUSPENDIDA", "3");
        try {
            mvc.perform(get(CONTEXT, a()).session(session)).andExpect(status().isForbidden());
            read(session, a(), SHARED).andExpect(status().isForbidden());
            read(session, b(), SHARED).andExpect(status().isOk());
        } finally {
            fixture.metadata(fixture.first, "ACTIVA", "3");
        }

        sql("UPDATE comun.membresias SET estado = 'REVOCADA', revocada_en = CURRENT_TIMESTAMP WHERE id = ?", membershipB);
        read(session, b(), onlyB).andExpect(status().isForbidden());
        mvc.perform(get(MINE).session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.empresas.length()").value(1))
                .andExpect(jsonPath("$.empresas[0].empresaId").value(a().toString()));

        sql("UPDATE comun.usuarios SET estado_cuenta = 'DESHABILITADA' WHERE id = ?", account.id());
        mvc.perform(get(CONTEXT, a()).session(session)).andExpect(status().isForbidden());
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
        sql("UPDATE comun.empresas SET estado = 'ACTIVA', version_estructura = '3' WHERE id = ?", company);
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
        var result = mvc.perform(post("/api/v1/identidad/sesion").with(csrf()).contentType(MediaType.APPLICATION_JSON)
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

    @TestConfiguration(proxyBeanMethods = false)
    static class Fixtures {
        @Bean TenantIsolationFixture probeData(TestDatabase database) throws Exception {
            return new TenantIsolationFixture(database);
        }
        @Bean @Primary TenantSecretSource probeSecrets(TenantIsolationFixture data) { return data.secrets(); }
        @Bean TestDelivery testDelivery() { return new TestDelivery(); }
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
