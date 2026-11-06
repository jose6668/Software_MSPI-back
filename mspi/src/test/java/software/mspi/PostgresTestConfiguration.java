package software.mspi;

import java.util.UUID;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;

@TestConfiguration(proxyBeanMethods = false)
class PostgresTestConfiguration {

	// postgres:17.11-alpine; use the digest to keep the test image reproducible.
	private static final String POSTGRES_IMAGE =
			"postgres@sha256:3540c0362c809df084ffac0ae69fe794515f9de92692f02178bcdce0ef0302ac";

	@Bean
	@ServiceConnection
	PostgreSQLContainer postgres() {
		return new PostgreSQLContainer(POSTGRES_IMAGE)
				.withDatabaseName("mspi_test")
				.withUsername("mspi_test")
				.withPassword(UUID.randomUUID().toString());
	}

}
