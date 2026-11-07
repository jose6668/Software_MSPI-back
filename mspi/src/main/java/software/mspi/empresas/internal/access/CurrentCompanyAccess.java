package software.mspi.empresas.internal.access;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import software.mspi.empresas.api.CompanyAccess;
import software.mspi.empresas.api.CompanyPermission;
import software.mspi.empresas.api.CompanyRole;
import software.mspi.empresas.api.TenantAccessPolicy;
import software.mspi.empresas.internal.migration.SchemaMigrator;
import software.mspi.identidad.api.ActiveSession;

@Service
final class CurrentCompanyAccess implements CompanyAccess, TenantAccessPolicy {

    private static final String OPERABLE_MEMBERSHIPS = """
            SELECT e.id, e.nombre, e.zona_horaria,
                   array_agg(r.rol_codigo ORDER BY r.rol_codigo) AS roles
            FROM comun.membresias m
            JOIN comun.empresas e ON e.id = m.empresa_id
            JOIN comun.membresia_roles r ON r.membresia_id = m.id
            WHERE m.usuario_id = :actor
              AND m.estado = 'ACTIVA' AND e.estado = 'ACTIVA' AND e.version_estructura = :version
            """;

    private final JdbcClient jdbc;
    private final ActiveSession session;

    CurrentCompanyAccess(JdbcClient jdbc, ActiveSession session) {
        this.jdbc = jdbc;
        this.session = session;
    }

    @Override
    public CompanyPage listMine(int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new IllegalArgumentException("Paginación empresarial inválida.");
        }
        UUID actor = session.requireActiveUser();
        var rows = query(actor, " GROUP BY e.id ORDER BY e.nombre, e.id LIMIT :limit OFFSET :offset")
                .param("limit", size + 1)
                .param("offset", (long) page * size)
                .query(CurrentCompanyAccess::membership)
                .list();
        boolean hasMore = rows.size() > size;
        return new CompanyPage(hasMore ? rows.subList(0, size) : rows, page, size, hasMore);
    }

    @Override
    public CompanyMembership require(UUID companyId, CompanyPermission permission) {
        return requireFor(session.requireActiveUser(), companyId, permission);
    }

    @Override
    public void requireAccess(UUID actorId, UUID companyId, CompanyPermission permission) {
        UUID actor = session.requireActiveUser();
        if (!actor.equals(actorId)) {
            throw denied();
        }
        requireFor(actor, companyId, permission);
    }

    @Override
    public void requireIndependentReviewer(UUID companyId, UUID deliveryResponsibleUserId) {
        UUID actor = session.requireActiveUser();
        requireFor(actor, companyId, CompanyPermission.REVIEW_ACTION);
        if (deliveryResponsibleUserId == null || actor.equals(deliveryResponsibleUserId)) {
            throw denied();
        }
    }

    private CompanyMembership requireFor(UUID actor, UUID company, CompanyPermission permission) {
        Objects.requireNonNull(company, "company");
        Objects.requireNonNull(permission, "permission");
        return query(actor, " AND e.id = :company GROUP BY e.id")
                .param("company", company)
                .query(CurrentCompanyAccess::membership)
                .optional()
                .filter(member -> permission.grantedTo(member.roles()))
                .orElseThrow(CurrentCompanyAccess::denied);
    }

    private JdbcClient.StatementSpec query(UUID actor, String suffix) {
        return jdbc.sql(OPERABLE_MEMBERSHIPS + suffix)
                .param("actor", actor)
                .param("version", SchemaMigrator.TENANT_STRUCTURE_VERSION);
    }

    private static CompanyMembership membership(ResultSet row, int index) throws SQLException {
        var array = row.getArray("roles");
        try {
            List<CompanyRole> roles = Arrays.stream((String[]) array.getArray())
                    .map(CompanyRole::valueOf).toList();
            return new CompanyMembership(row.getObject("id", UUID.class), row.getString("nombre"),
                    row.getString("zona_horaria"), roles);
        } finally {
            array.free();
        }
    }

    private static AccessDeniedException denied() {
        return new AccessDeniedException("Acceso empresarial no autorizado.");
    }
}
