package software.mspi;

import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SessionRevocationMigrationTests {

    @Test
    void upgradesExistingAccountsAndRevokesOnlyOnTransitionsOutOfEnabledState() throws Exception {
        // Separate disposable database: never rewind the shared Spring test schema.
        try (var postgres = new PostgresTestConfiguration().postgres()) {
            postgres.start();
            var database = new TestDatabase(postgres, "12");
            UUID active = UUID.randomUUID();
            UUID inactive = UUID.randomUUID();
            try (var connection = database.admin(); var statement = connection.prepareStatement("""
                    INSERT INTO comun.usuarios(id, nombre_visible, correo, clave_correo, estado_cuenta)
                    VALUES (?, 'Prueba de migracion', ?, ?, 'DESHABILITADA')
                    """)) {
                for (UUID id : new UUID[]{active, inactive}) {
                    statement.setObject(1, id);
                    statement.setString(2, id + "@example.test");
                    statement.setString(3, id + "@example.test");
                    statement.executeUpdate();
                }
            }
            // A valid enabled account requires a verified channel and a supported hash.
            var encoder = new org.springframework.security.crypto.password.Pbkdf2PasswordEncoder("", 16, 600_000,
                    org.springframework.security.crypto.password.Pbkdf2PasswordEncoder.SecretKeyFactoryAlgorithm.PBKDF2WithHmacSHA256);
            try (var connection = database.admin(); var statement = connection.prepareStatement("""
                    UPDATE comun.usuarios SET estado_cuenta = 'HABILITADA', correo_verificado_en = CURRENT_TIMESTAMP,
                        hash_credencial = ? WHERE id = ?
                    """)) {
                statement.setString(1, "{pbkdf2-sha256-v1}" + encoder.encode(UUID.randomUUID().toString()));
                statement.setObject(2, active);
                statement.executeUpdate();
            }
            var revocationMigration = Flyway.configure().configuration(database.commonFlyway().getConfiguration())
                    .target("13").load();
            assertThat(revocationMigration.migrate().migrationsExecuted).isEqualTo(1);
            try (var connection = database.admin(); var statement = connection.createStatement()) {
                try (var rows = statement.executeQuery("SELECT id, version_credencial FROM comun.usuarios")) {
                    while (rows.next()) {
                        assertThat(rows.getLong(2)).isEqualTo(rows.getObject(1).equals(active) ? 0L : 1L);
                    }
                }
                statement.executeUpdate("UPDATE comun.usuarios SET estado_cuenta = estado_cuenta");
                statement.executeUpdate("UPDATE comun.usuarios SET estado_cuenta = 'DESHABILITADA' WHERE id = '" + active + "'");
                try (var rows = statement.executeQuery("SELECT version_credencial FROM comun.usuarios")) {
                    while (rows.next()) {
                        assertThat(rows.getLong(1)).isEqualTo(1L);
                    }
                }
            }
            assertThat(revocationMigration.migrate().migrationsExecuted).isZero();
        }
    }
}
