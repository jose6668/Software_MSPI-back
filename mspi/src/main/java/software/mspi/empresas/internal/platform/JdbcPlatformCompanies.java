package software.mspi.empresas.internal.platform;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.ZoneId;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import software.mspi.empresas.api.InvitationFailure;
import software.mspi.empresas.api.PlatformCompanies;
import software.mspi.empresas.api.PlatformFailure;
import software.mspi.empresas.internal.invitations.InvitationDelivery;
import software.mspi.empresas.internal.migration.MigrationScope;
import software.mspi.empresas.internal.migration.SchemaMigrator;
import software.mspi.empresas.internal.migration.TenantRuntimeVerifier;
import software.mspi.empresas.internal.tenancy.EnvironmentTenantSecretSource;
import software.mspi.empresas.internal.tenancy.TenantSecretSource;
import software.mspi.identidad.api.ActiveSession;
import software.mspi.identidad.api.EmailKey;

@Service
final class JdbcPlatformCompanies implements PlatformCompanies {

    private static final String VERSION = SchemaMigrator.TENANT_STRUCTURE_VERSION;

    private final JdbcClient jdbc;
    private final ActiveSession session;
    private final TenantSecretSource secrets;
    private final ObjectProvider<InvitationDelivery> mail;
    private final TransactionTemplate transactions;

    JdbcPlatformCompanies(JdbcClient jdbc, ActiveSession session, ObjectProvider<TenantSecretSource> secrets,
                          Environment environment, ObjectProvider<InvitationDelivery> mail,
                          @Qualifier("commonTransactionManager") PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.session = session;
        this.secrets = secrets.getIfAvailable(() -> new EnvironmentTenantSecretSource(environment));
        this.mail = mail;
        this.transactions = new TransactionTemplate(manager);
    }

    @Override
    public Page list(int page, int size) {
        UUID actor = session.requireActiveUser();
        var rows = translate(() -> query(actor, null, size + 1, (long) page * size).query(JdbcPlatformCompanies::company).list());
        boolean hasMore = rows.size() > size;
        return new Page(hasMore ? rows.subList(0, size) : rows, page, size, hasMore);
    }

    @Override
    public Company get(UUID id) {
        return read(session.requireActiveUser(), id);
    }

    @Override
    public Company reserve(Reservation input, UUID key, UUID correlation) {
        UUID actor = session.requireActiveUser();
        if (!ZoneId.getAvailableZoneIds().contains(input.zonaHoraria())) {
            throw new PlatformFailure(PlatformFailure.Reason.INVALID_ZONE);
        }
        return translate(() -> transactions.execute(status -> {
            UUID id = jdbc.sql("""
                    SELECT comun.reservar_empresa(:actor, :id, :key, :name, :description, :zone, :email,
                        :emailKey, :correlation)
                    """)
                    .param("actor", actor).param("id", UUID.randomUUID()).param("key", key)
                    .param("name", input.nombre()).param("description", input.descripcion())
                    .param("zone", input.zonaHoraria()).param("email", input.contactoInicial())
                    .param("emailKey", EmailKey.normalize(input.contactoInicial())).param("correlation", correlation)
                    .query(UUID.class).single();
            return read(actor, id);
        }));
    }

    @Override
    public Company complete(UUID id, long revision, boolean reissue, UUID correlation) {
        UUID actor = session.requireActiveUser();
        var current = read(actor, id);
        requireRevision(current, revision);
        if (reissue) {
            if (!"ACTIVA".equals(current.estado()) || current.invitacionInicialId() == null) {
                throw conflict();
            }
        } else if (!canComplete(current)) {
            throw conflict();
        }

        try {
            verifyRuntime(current);
            InvitationDelivery delivery = mail.getIfAvailable();
            if (delivery == null) {
                throw new PlatformFailure(PlatformFailure.Reason.MAIL_DISABLED);
            }
            return activate(actor, id, revision, reissue, correlation, delivery);
        } catch (InvitationFailure failure) {
            if (!reissue && failure.reason() == InvitationFailure.Reason.DELIVERY) {
                recordFailure(actor, id, revision, "ENVIO_INICIAL", correlation);
            }
            throw failure;
        } catch (PlatformFailure failure) {
            if (!reissue && failure.reason() == PlatformFailure.Reason.INCOMPATIBLE) {
                recordFailure(actor, id, revision, "VERIFICACION_RUNTIME", correlation);
            } else if (!reissue && failure.reason() == PlatformFailure.Reason.MAIL_DISABLED) {
                recordFailure(actor, id, revision, "INVITACIONES_DESHABILITADAS", correlation);
            }
            throw failure;
        }
    }

    @Override
    public Company changeAvailability(UUID id, long revision, boolean reactivate, String reason, UUID correlation) {
        UUID actor = session.requireActiveUser();
        if (reactivate) {
            var current = read(actor, id);
            requireRevision(current, revision);
            if (!"SUSPENDIDA".equals(current.estado())) {
                throw conflict();
            }
            verifyRuntime(current);
        }
        return translate(() -> transactions.execute(status -> {
            jdbc.sql("""
                    SELECT comun.cambiar_disponibilidad_empresa(:actor, :id, :revision, :reactivate,
                        :reason, :version, :correlation)
                    """)
                    .param("actor", actor).param("id", id).param("revision", revision)
                    .param("reactivate", reactivate).param("reason", reason).param("version", VERSION)
                    .param("correlation", correlation).query(Boolean.class).single();
            return read(actor, id);
        }));
    }

    private Company activate(UUID actor, UUID id, long revision, boolean reissue, UUID correlation,
                             InvitationDelivery delivery) {
        var pending = delivery.create();
        return translate(() -> transactions.execute(status -> {
            String recipient = jdbc.sql("""
                    SELECT comun.activar_con_invitacion_inicial(:actor, :id, :revision, :invitation,
                        :fingerprint, :seconds, :version, :reissue, :correlation)
                    """)
                    .param("actor", actor).param("id", id).param("revision", revision)
                    .param("invitation", pending.id()).param("fingerprint", pending.digest())
                    .param("seconds", pending.validitySeconds()).param("version", VERSION)
                    .param("reissue", reissue).param("correlation", correlation).query(String.class).single();
            delivery.send(recipient, pending);
            return read(actor, id);
        }));
    }

    private void recordFailure(UUID actor, UUID id, long revision, String code, UUID correlation) {
        translate(() -> jdbc.sql("SELECT comun.registrar_fallo_de_alta(:actor, :id, :revision, :code, :correlation)")
                .param("actor", actor).param("id", id).param("revision", revision)
                .param("code", code).param("correlation", correlation).query(Boolean.class).single());
    }

    private void verifyRuntime(Company company) {
        if (!VERSION.equals(company.versionEstructura())) {
            throw new PlatformFailure(PlatformFailure.Reason.INCOMPATIBLE);
        }
        var scope = MigrationScope.tenant(company.empresaId());
        try {
            var secret = secrets.load(company.empresaId());
            if (!scope.runtimeRole().equals(secret.username())) {
                throw new IllegalArgumentException("La credencial de runtime no corresponde a la empresa.");
            }
            TenantRuntimeVerifier.verify(scope, secret.jdbcUrl(), secret.password());
        } catch (SQLException | IllegalArgumentException | IllegalStateException failure) {
            throw new PlatformFailure(PlatformFailure.Reason.INCOMPATIBLE);
        }
    }

    private Company read(UUID actor, UUID id) {
        return translate(() -> query(actor, id, 1, 0).query(JdbcPlatformCompanies::company).optional()
                .orElseThrow(() -> new PlatformFailure(PlatformFailure.Reason.MISSING)));
    }

    private JdbcClient.StatementSpec query(UUID actor, UUID id, int limit, long offset) {
        return jdbc.sql("SELECT * FROM comun.empresas_de_plataforma(:actor, :id, :limit, :offset)")
                .param("actor", actor).param("id", id).param("limit", limit).param("offset", offset);
    }

    private static Company company(ResultSet row, int index) throws SQLException {
        return new Company(row.getObject("id", UUID.class), row.getString("nombre"), row.getString("descripcion"),
                row.getString("zona_horaria"), row.getString("estado"), row.getString("version_estructura"),
                row.getLong("revision_registro"), row.getString("paso_preparacion"), row.getString("resultado_preparacion"),
                row.getObject("referencia_fallo", UUID.class), row.getString("motivo_estado"),
                row.getObject("invitacion_inicial_id", UUID.class));
    }

    private static boolean canComplete(Company company) {
        return ("EN_PREPARACION".equals(company.estado()) || "ERROR_APROVISIONAMIENTO".equals(company.estado()))
                && "VERIFICADO".equals(company.resultadoPreparacion()) && VERSION.equals(company.versionEstructura());
    }

    private static void requireRevision(Company company, long revision) {
        if (company.revision() != revision) {
            throw conflict();
        }
    }

    private static PlatformFailure conflict() {
        return new PlatformFailure(PlatformFailure.Reason.CONFLICT);
    }

    private static <T> T translate(Supplier<T> query) {
        try {
            return query.get();
        } catch (DataAccessException failure) {
            if (failure.getMostSpecificCause() instanceof SQLException sql) {
                switch (sql.getSQLState()) {
                    case "P1001" -> throw new AccessDeniedException("Operación de plataforma no autorizada.");
                    case "P1002" -> throw conflict();
                    case "P1003" -> throw new PlatformFailure(PlatformFailure.Reason.MISSING);
                    default -> throw failure;
                }
            }
            throw failure;
        }
    }
}
