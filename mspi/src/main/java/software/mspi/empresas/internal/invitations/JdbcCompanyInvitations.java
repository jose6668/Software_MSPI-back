package software.mspi.empresas.internal.invitations;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import software.mspi.empresas.api.CompanyInvitations;
import software.mspi.empresas.api.CompanyRole;
import software.mspi.empresas.api.InvitationFailure;
import software.mspi.empresas.internal.migration.SchemaMigrator;
import software.mspi.identidad.api.ActiveSession;
import software.mspi.identidad.api.EmailKey;
import software.mspi.identidad.api.PasswordPolicy;
import software.mspi.identidad.api.InvitedAccountPassword;

@Service
@ConditionalOnProperty(name = "mspi.invitations.enabled", havingValue = "true")
class JdbcCompanyInvitations implements CompanyInvitations {

    private final JdbcClient jdbc;
    private final ActiveSession session;
    private final InvitedAccountPassword passwords;
    private final InvitationDelivery delivery;
    private final InvitationSettings settings;

    JdbcCompanyInvitations(JdbcClient jdbc, ActiveSession session, InvitedAccountPassword passwords,
                           InvitationDelivery delivery, InvitationSettings settings) {
        this.jdbc = jdbc;
        this.session = session;
        this.passwords = passwords;
        this.delivery = delivery;
        this.settings = settings;
    }

    @Override
    @Transactional(transactionManager = "commonTransactionManager")
    public Issued issue(UUID companyId, String email, List<CompanyRole> roles) {
        UUID actor = session.requireActiveUser();
        var invitation = delivery.create();
        var expires = jdbc.sql("""
                SELECT comun.emitir_invitacion(:actor, :company, :id, :email, :key, :fingerprint,
                    CAST(:roles AS text[]), :seconds, :version)
                """)
                .param("actor", actor).param("company", companyId).param("id", invitation.id())
                .param("email", email).param("key", EmailKey.normalize(email))
                .param("fingerprint", invitation.digest())
                .param("roles", roles.stream().map(CompanyRole::name).toArray(String[]::new), Types.ARRAY)
                .param("seconds", invitation.validitySeconds())
                .param("version", SchemaMigrator.TENANT_STRUCTURE_VERSION)
                .query(OffsetDateTime.class).optional()
                .orElseThrow(() -> new AccessDeniedException("Acceso empresarial no autorizado."))
                .toInstant();
        delivery.send(email, invitation);
        return new Issued(invitation.id(), expires);
    }

    @Override
    public void revoke(UUID companyId, UUID invitationId) {
        UUID actor = session.requireActiveUser();
        boolean revoked = jdbc.sql("SELECT comun.revocar_invitacion(:actor, :company, :id, :version)")
                .param("actor", actor).param("company", companyId).param("id", invitationId)
                .param("version", SchemaMigrator.TENANT_STRUCTURE_VERSION)
                .query(Boolean.class).optional()
                .orElseThrow(() -> new AccessDeniedException("Acceso empresarial no autorizado."));
        if (!revoked) {
            throw unavailable();
        }
    }

    @Override
    public Accepted accept(String token) {
        return consume(session.requireActiveUser(), null, null, null, InvitationDelivery.fingerprint(token));
    }

    @Override
    public Accepted enroll(String token, String displayName, String password) {
        byte[] digest = InvitationDelivery.fingerprint(token);
        boolean eligible = jdbc.sql("SELECT comun.invitacion_admite_alta(:fingerprint, :version)")
                .param("fingerprint", digest).param("version", SchemaMigrator.TENANT_STRUCTURE_VERSION)
                .query(Boolean.class).single();
        if (!eligible) {
            throw unavailable();
        }
        if (!PasswordPolicy.accepts(password, settings.minPasswordLength())) {
            throw new InvitationFailure(InvitationFailure.Reason.PASSWORD_POLICY);
        }
        return consume(null, UUID.randomUUID(), displayName, passwords.encode(password), digest);
    }

    private Accepted consume(UUID actor, UUID newUser, String displayName, String passwordHash, byte[] digest) {
        try {
            return jdbc.sql("""
                    SELECT * FROM comun.aceptar_invitacion(:actor, :newUser, :name, :hash,
                        :fingerprint, :membership, :version)
                    """)
                    .param("actor", actor).param("newUser", newUser).param("name", displayName)
                    .param("hash", passwordHash).param("fingerprint", digest).param("membership", UUID.randomUUID())
                    .param("version", SchemaMigrator.TENANT_STRUCTURE_VERSION)
                    .query(JdbcCompanyInvitations::accepted).optional().orElseThrow(JdbcCompanyInvitations::unavailable);
        } catch (DataAccessException failure) {
            if (failure.getMostSpecificCause() instanceof SQLException sql && "P0901".equals(sql.getSQLState())) {
                throw unavailable();
            }
            throw failure;
        }
    }

    private static Accepted accepted(ResultSet row, int index) throws SQLException {
        var array = row.getArray("roles");
        try {
            List<CompanyRole> roles = Arrays.stream((String[]) array.getArray()).map(CompanyRole::valueOf).toList();
            return new Accepted(row.getObject("empresa_id", UUID.class), row.getObject("membresia_id", UUID.class),
                    row.getObject("usuario_id", UUID.class), roles);
        } finally {
            array.free();
        }
    }

    private static InvitationFailure unavailable() {
        return new InvitationFailure(InvitationFailure.Reason.UNAVAILABLE);
    }
}
