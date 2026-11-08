package software.mspi.gestion.internal.web;

import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;

class SqlBudgetErrorTests {
    @ParameterizedTest @ValueSource(strings = {"57014", "55P03", "53300", "53400", "25P03", "25P04"})
    void sqlBudgetErrorsAreRedactedAndRetryable(String state) throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/v1/test");
        var response = new MockHttpServletResponse();
        new ApiCorrelationFilter().doFilter(request, response, (req,res) -> {
            new ApiErrors(JsonMapper.builder().build()).resolve(request, response, null,
                    new IllegalStateException("outer", new SQLException("private SQL and credentials", state)));
        });
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getHeader("Retry-After")).isEqualTo("1");
        assertThat(response.getContentAsString()).contains("BASE_OCUPADA").doesNotContain("private SQL", "credentials");
    }
    @Test void nullSqlStateDoesNotBreakTheErrorHandler() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/v1/test");
        var response = new MockHttpServletResponse();
        new ApiCorrelationFilter().doFilter(request, response, (req,res) -> {
            new ApiErrors(JsonMapper.builder().build()).resolve(request, response, null, new SQLException("test"));
        });
        assertThat(response.getStatus()).isEqualTo(500);
    }
    @Test void poolWaitWithoutSqlStateIsAlsoRetryable() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/v1/test");
        var response = new MockHttpServletResponse();
        new ApiCorrelationFilter().doFilter(request, response, (req,res) -> {
            new ApiErrors(JsonMapper.builder().build()).resolve(request, response, null, new SQLTransientConnectionException("pool full"));
        });
        assertThat(response.getStatus()).isEqualTo(503);
    }
}
