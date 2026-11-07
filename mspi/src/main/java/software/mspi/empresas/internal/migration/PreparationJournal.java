package software.mspi.empresas.internal.migration;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

/** Each checkpoint is persisted while the caller retains its database session lock. */
final class PreparationJournal {

    enum Step { APROVISIONAR, CREDENCIALES, MIGRAR, VERIFICAR }
    enum Result { ESPERA_CREDENCIALES, FALLIDO, VERIFICADO }

    private final Connection connection;
    private final MigrationScope scope;
    private final UUID attemptId = UUID.randomUUID();
    private long registryRevision;

    PreparationJournal(Connection connection, MigrationScope scope) {
        this.connection = connection;
        this.scope = scope;
    }

    UUID attemptId() {
        return attemptId;
    }

    void begin() throws SQLException {
        try (var statement = connection.prepareStatement("""
                UPDATE comun.empresas SET estado = 'EN_PREPARACION', version_estructura = NULL,
                    revision_registro = revision_registro + 1
                WHERE id = ? AND esquema_interno = ? AND estado IN ('EN_PREPARACION', 'ERROR_APROVISIONAMIENTO')
                RETURNING revision_registro
                """)) {
            statement.setObject(1, scope.companyId());
            statement.setString(2, scope.schema());
            try (var result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new IllegalArgumentException("Se requiere una empresa reservada o con fallo de aprovisionamiento.");
                }
                registryRevision = result.getLong(1);
            }
        }
        try (var statement = connection.prepareStatement("""
                UPDATE comun.intentos_preparacion SET resultado = 'INTERRUMPIDO', terminado_en = CURRENT_TIMESTAMP
                WHERE empresa_id = ? AND resultado = 'EN_CURSO'
                """)) {
            statement.setObject(1, scope.companyId());
            statement.executeUpdate();
        }
        try (var statement = connection.prepareStatement("""
                INSERT INTO comun.intentos_preparacion (id, empresa_id, version_objetivo, paso, resultado)
                VALUES (?, ?, ?, 'APROVISIONAR', 'EN_CURSO')
                """)) {
            statement.setObject(1, attemptId);
            statement.setObject(2, scope.companyId());
            statement.setString(3, SchemaMigrator.TENANT_STRUCTURE_VERSION);
            statement.executeUpdate();
        }
        connection.commit();
    }

    void advance(Step step) throws SQLException {
        try (var statement = connection.prepareStatement("""
                UPDATE comun.intentos_preparacion SET paso = ? WHERE id = ?
                """)) {
            statement.setString(1, step.name());
            statement.setObject(2, attemptId);
            statement.executeUpdate();
        }
        connection.commit();
    }

    void finish(Result result) throws SQLException {
        try (var statement = connection.prepareStatement("""
                UPDATE comun.empresas SET estado = ?, version_estructura = ?, revision_registro = revision_registro + 1
                WHERE id = ? AND estado = 'EN_PREPARACION' AND revision_registro = ?
                """)) {
            statement.setString(1, result == Result.FALLIDO ? "ERROR_APROVISIONAMIENTO" : "EN_PREPARACION");
            statement.setString(2, result == Result.VERIFICADO ? SchemaMigrator.TENANT_STRUCTURE_VERSION : null);
            statement.setObject(3, scope.companyId());
            statement.setLong(4, registryRevision);
            if (statement.executeUpdate() != 1) {
                throw new SQLException("El registro de la empresa cambió durante la preparación.");
            }
        }
        try (var statement = connection.prepareStatement("""
                UPDATE comun.intentos_preparacion SET resultado = ?, terminado_en = CURRENT_TIMESTAMP WHERE id = ?
                """)) {
            statement.setString(1, result.name());
            statement.setObject(2, attemptId);
            statement.executeUpdate();
        }
        connection.commit();
    }
}
