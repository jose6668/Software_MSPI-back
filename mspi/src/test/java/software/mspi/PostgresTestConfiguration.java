package software.mspi;

import java.util.UUID;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;

@TestConfiguration(proxyBeanMethods = false)
class PostgresTestConfiguration {

	// postgres:17.11-alpine; use the digest to keep the test image reproducible.
	private static final String POSTGRES_IMAGE =
			"postgres@sha256:3540c0362c809df084ffac0ae69fe794515f9de92692f02178bcdce0ef0302ac";

	@Bean
	PostgreSQLContainer postgres() {
		return new PostgreSQLContainer(POSTGRES_IMAGE)
				.withDatabaseName("mspi_test")
				.withUsername("mspi_test")
				.withPassword(UUID.randomUUID().toString());
	}

	@Bean
	TestDatabase testDatabase(PostgreSQLContainer postgres) throws Exception {
		return new TestDatabase(postgres);
	}

	@Bean
	JdbcConnectionDetails jdbcConnectionDetails(TestDatabase database) {
		return new JdbcConnectionDetails() {
			@Override
			public String getJdbcUrl() {
				return database.url();
			}

			@Override
			public String getUsername() {
				return "mspi_common_runtime";
			}

			@Override
			public String getPassword() {
				return database.runtimePassword();
			}
		};
	}

}
