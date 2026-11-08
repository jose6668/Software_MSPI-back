package software.mspi.gestion.internal.web;

import java.time.Clock;
import java.util.concurrent.atomic.AtomicInteger;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApiLoadFilterTests {
    private ApiLoadFilter filter(int rate, int sessions) {
        var settings = ApiLoadGuardTests.settings(rate, 10, 2, 1, sessions);
        return new ApiLoadFilter(new ApiLoadGuard(settings, Clock.systemUTC()), settings,
                new ApiErrors(JsonMapper.builder().build()));
    }

    @Test
    void forgedForwardedHeadersCannotResetTheAddressBudgetAndRejectsBeforeDownstream() throws Exception {
        var filter = filter(2, 1);
        var calls = new AtomicInteger();
        for (int i = 0; i < 3; i++) {
            var request = new MockHttpServletRequest("GET", "/api/v1");
            request.addHeader("X-Forwarded-For", "192.0.2." + i);
            var response = new MockHttpServletResponse();
            filter.doFilter(request, response, (req, res) -> calls.incrementAndGet());
            if (i == 2) {
                assertThat(response.getStatus()).isEqualTo(429);
                assertThat(response.getHeader("Retry-After")).isNotBlank();
                assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
                assertThat(response.getContentAsString()).contains("DEMASIADAS_SOLICITUDES");
                assertThat(request.getSession(false)).isNull();
            }
        }
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void sessionCreationIsBoundedAndInvalidationReleasesCapacity() throws Exception {
        var filter = filter(100, 1);
        var sessions = new java.util.ArrayList<HttpSession>();
        var first = new MockHttpServletRequest("GET", "/api/v1/identidad/csrf");
        filter.doFilter(first, new MockHttpServletResponse(), (req, res) -> {
            var request = (HttpServletRequest) req;
            sessions.add(request.getSession());
            assertThat(request.getSession()).isSameAs(sessions.get(0));
        });
        var next = new MockHttpServletRequest("GET", "/api/v1/identidad/csrf");
        var refused = new MockHttpServletResponse();
        filter.doFilter(next, refused, (req, res) -> ((HttpServletRequest) req).getSession());
        assertThat(refused.getStatus()).isEqualTo(503);
        assertThat(next.getSession(false)).isNull();
        sessions.get(0).invalidate();
        var recovered = new MockHttpServletRequest("GET", "/api/v1/identidad/csrf");
        filter.doFilter(recovered, new MockHttpServletResponse(), (req, res) -> ((HttpServletRequest) req).getSession());
        assertThat(recovered.getSession(false)).isNotNull();
    }

    @Test
    void exceptionsReleaseRequestCapacity() throws Exception {
        var filter = filter(100, 1);
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> filter.doFilter(new MockHttpServletRequest("GET", "/api/v1"),
                    new MockHttpServletResponse(), (req, res) -> { throw new ServletException("test-only"); }))
                    .isInstanceOf(ServletException.class);
        }
    }

    @Test
    void bodyLimitChecksActualBytesAndReleasesCapacity() throws Exception {
        var filter = filter(100, 1);
        var request = new MockHttpServletRequest("POST", "/api/v1/test");
        request.setContent(new byte[1025]);
        request = new MockHttpServletRequestWrapperWithoutLength(request);
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> req.getInputStream().readAllBytes());
        assertThat(response.getStatus()).isEqualTo(413);
    }

    private static final class MockHttpServletRequestWrapperWithoutLength extends MockHttpServletRequest {
        MockHttpServletRequestWrapperWithoutLength(MockHttpServletRequest source) {
            setMethod(source.getMethod()); setRequestURI(source.getRequestURI()); setContent(source.getContentAsByteArray());
        }
        @Override public long getContentLengthLong() { return -1; }
        @Override public int getContentLength() { return -1; }
    }
}
