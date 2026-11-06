package software.mspi;

import javax.sql.DataSource;

import jakarta.persistence.EntityManagerFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
class MspiApplicationTests {

	@Autowired
	private DataSource dataSource;

	@Autowired
	private EntityManagerFactory entityManagerFactory;

	@Autowired
	private ApplicationContext context;

	@Test
	void contextLoads() throws Exception {
		try (var connection = dataSource.getConnection()) {
			assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
			assertThat(connection.getMetaData().getDatabaseMajorVersion()).isEqualTo(17);
		}

		try (var entityManager = entityManagerFactory.createEntityManager()) {
			assertThat(entityManager.createNativeQuery("select current_database()").getSingleResult())
					.isEqualTo("mspi_test");
		}

		assertThat(context.getBeansOfType(Flyway.class)).isEmpty();
		try (var connection = dataSource.getConnection();
				var statement = connection.createStatement();
				var result = statement.executeQuery("SELECT current_user")) {
			assertThat(result.next()).isTrue();
			assertThat(result.getString(1)).isEqualTo("mspi_common_runtime");
		}
	}

}
