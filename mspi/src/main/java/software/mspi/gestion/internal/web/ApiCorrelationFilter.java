package software.mspi.gestion.internal.web;

import java.io.IOException;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

final class ApiCorrelationFilter extends OncePerRequestFilter {

    static final String HEADER = "X-Correlation-Id";
    private static final String ATTRIBUTE = ApiCorrelationFilter.class.getName() + ".id";
    private static final String MDC_KEY = "correlationId";

    static UUID id(HttpServletRequest request) {
        return (UUID) request.getAttribute(ATTRIBUTE);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        UUID id = UUID.randomUUID();
        request.setAttribute(ATTRIBUTE, id);
        response.setHeader(HEADER, id.toString());
        String previous = MDC.get(MDC_KEY);
        MDC.put(MDC_KEY, id.toString());
        try {
            chain.doFilter(request, response);
        } finally {
            if (previous == null) {
                MDC.remove(MDC_KEY);
            } else {
                MDC.put(MDC_KEY, previous);
            }
        }
    }
}
