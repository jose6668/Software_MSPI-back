package software.mspi.empresas.internal.administration;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.dao.DataAccessException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import software.mspi.auditoria.api.ActivityLog.Activity;
import software.mspi.auditoria.api.ActivityLog.Filters;
import software.mspi.auditoria.api.ActivityLog.Source;
import software.mspi.auditoria.api.ActivityLog.Window;
import software.mspi.empresas.api.CompanyAdministration;
import software.mspi.empresas.api.CompanyAdministrationFailure;
import software.mspi.empresas.api.CompanyRole;
import software.mspi.empresas.internal.migration.SchemaMigrator;
import software.mspi.identidad.api.ActiveSession;

@Service
final class JdbcCompanyAdministration implements CompanyAdministration {

    private final JdbcClient jdbc;
    private final ActiveSession session;

    JdbcCompanyAdministration(JdbcClient jdbc, ActiveSession session) {
        this.jdbc = jdbc;
        this.session = session;
    }

    @Override
    public List<Activity> activity(UUID companyId, Filters filters, Window window, int limit) {
        return translate(() -> forCompany("""
                SELECT * FROM comun.actividad_administrativa(:actor, :company, :version, :from, :until,
                    :eventActor, :action, :type, :object, :before, :includeSameTime, :beforeId, :limit)
                """, companyId)
                .param("from", timestamp(filters.from())).param("until", timestamp(filters.until()))
                .param("eventActor", filters.actor()).param("action", filters.action())
                .param("type", filters.objectType()).param("object", filters.objectId())
                .param("before", timestamp(window.before())).param("includeSameTime", window.includeSameTime())
                .param("beforeId", window.beforeId() == null ? null : Long.valueOf(window.beforeId()))
                .param("limit", limit)
                .query((row, index) -> new Activity(row.getString("evento_id"), Source.ADMINISTRATIVA,
                        row.getObject("actor_id", UUID.class), row.getString("accion"), row.getString("tipo_objeto"),
                        row.getObject("objeto_id", UUID.class), row.getString("resultado"), row.getString("motivo"),
                        row.getObject("correlacion_id", UUID.class), instant(row, "registrado_en"))).list());
    }

    @Override
    public Configuration configuration(UUID companyId) {
        return translate(() -> forCompany("SELECT * FROM comun.configuracion_empresa(:actor, :company, :version)", companyId)
                .query((row, index) -> new Configuration(row.getString("nombre"), row.getString("descripcion"),
                        row.getString("zona_horaria"), row.getLong("revision"))).single());
    }

    @Override
    public void configure(UUID companyId, Configuration input, UUID correlation) {
        if (!ZoneId.getAvailableZoneIds().contains(input.zonaHoraria())) {
            throw new CompanyAdministrationFailure(CompanyAdministrationFailure.Reason.INVALID_ZONE);
        }
        translate(() -> forCompany("""
                SELECT comun.configurar_empresa(:actor, :company, :version, :revision, :name, :description, :zone, :correlation)
                """, companyId)
                .param("revision", input.revision()).param("name", input.nombre()).param("description", input.descripcion())
                .param("zone", input.zonaHoraria()).param("correlation", correlation).query(Boolean.class).single());
    }

    @Override
    public Page<Member> members(UUID companyId, int page, int size) {
        return page("SELECT * FROM comun.membresias_empresa(:actor, :company, :version, :limit, :offset)",
                companyId, page, size, (row, index) -> new Member(row.getObject("id", UUID.class), row.getString("nombre"),
                        row.getString("correo"), roles(row), row.getString("estado"), instant(row, "revocada_en"),
                        row.getLong("revision")));
    }

    @Override
    public Page<Invitation> invitations(UUID companyId, int page, int size) {
        return page("SELECT * FROM comun.invitaciones_empresa(:actor, :company, :version, :limit, :offset)",
                companyId, page, size, (row, index) -> new Invitation(row.getObject("id", UUID.class),
                        row.getString("correo"), roles(row), row.getString("estado"), instant(row, "creada_en"),
                        instant(row, "vence_en"), instant(row, "aceptada_en"), instant(row, "revocada_en")));
    }

    @Override
    public Page<Responsible> responsibles(UUID companyId, int page, int size) {
        return page("SELECT * FROM comun.responsables_empresa(:actor, :company, :version, :limit, :offset)",
                companyId, page, size, (row, index) -> new Responsible(row.getObject("id", UUID.class), row.getString("nombre")));
    }

    @Override
    public void changeRoles(UUID companyId, UUID memberId, long revision, List<CompanyRole> roles, String reason,
                            UUID correlation) {
        change(companyId, memberId, revision, roles, false, reason, correlation);
    }

    @Override
    public void revoke(UUID companyId, UUID memberId, long revision, String reason, UUID correlation) {
        change(companyId, memberId, revision, List.of(), true, reason, correlation);
    }

    private void change(UUID companyId, UUID memberId, long revision, List<CompanyRole> roles, boolean revoke,
                        String reason, UUID correlation) {
        translate(() -> forCompany("""
                SELECT comun.cambiar_membresia(:actor, :company, :version, :id, :revision,
                    CAST(:roles AS text[]), :revoke, :reason, :correlation)
                """, companyId).param("id", memberId).param("revision", revision)
                .param("roles", roles.stream().map(CompanyRole::name).toArray(String[]::new), Types.ARRAY)
                .param("revoke", revoke).param("reason", reason).param("correlation", correlation)
                .query(Boolean.class).single());
    }

    private <T> Page<T> page(String sql, UUID companyId, int page, int size, RowMapper<T> mapper) {
        List<T> rows = translate(() -> forCompany(sql, companyId).param("limit", size + 1)
                .param("offset", (long) page * size).query(mapper).list());
        boolean hasMore = rows.size() > size;
        return new Page<>(hasMore ? rows.subList(0, size) : rows, page, size, hasMore);
    }

    private JdbcClient.StatementSpec forCompany(String sql, UUID companyId) {
        return jdbc.sql(sql).param("actor", session.requireActiveUser()).param("company", companyId)
                .param("version", SchemaMigrator.TENANT_STRUCTURE_VERSION);
    }

    private static List<CompanyRole> roles(ResultSet row) throws SQLException {
        var array = row.getArray("roles");
        try {
            return Arrays.stream((String[]) array.getArray()).map(CompanyRole::valueOf).toList();
        } finally {
            array.free();
        }
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        var date = row.getObject(column, OffsetDateTime.class);
        return date == null ? null : date.toInstant();
    }

    private static OffsetDateTime timestamp(Instant time) {
        return time == null ? null : time.atOffset(ZoneOffset.UTC);
    }

    private static <T> T translate(Supplier<T> operation) {
        try {
            return operation.get();
        } catch (DataAccessException failure) {
            if (failure.getMostSpecificCause() instanceof SQLException sql) {
                switch (sql.getSQLState()) {
                    case "P1101" -> throw new AccessDeniedException("Acceso empresarial no autorizado.");
                    case "P1102" -> throw new OptimisticLockingFailureException("Revisión desactualizada.");
                    case "P1103" -> throw new CompanyAdministrationFailure(CompanyAdministrationFailure.Reason.MISSING);
                    case "P1104" -> throw new CompanyAdministrationFailure(CompanyAdministrationFailure.Reason.LAST_ADMINISTRATOR);
                    case "P1105" -> throw new CompanyAdministrationFailure(CompanyAdministrationFailure.Reason.REVOKED);
                    default -> throw failure;
                }
            }
            throw failure;
        }
    }
}
