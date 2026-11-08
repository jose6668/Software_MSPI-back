package software.mspi;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"test", "https-internet"})
@Import(PostgresTestConfiguration.class)
class HttpsInternetIntegrationTests extends AbstractHttpsIntegrationTests {}
