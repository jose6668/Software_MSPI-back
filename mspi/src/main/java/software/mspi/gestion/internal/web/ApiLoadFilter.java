package software.mspi.gestion.internal.web;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import software.mspi.identidad.api.SessionIdentity;

import static software.mspi.gestion.internal.web.ApiLoadGuard.Traffic.*;

final class ApiLoadFilter extends OncePerRequestFilter {
    private static final String SESSION_LEASE = ApiLoadFilter.class.getName() + ".lease";
    private final ApiLoadGuard guard;
    private final ApiLoadSettings settings;
    private final ApiErrors errors;
    private final boolean api;

    ApiLoadFilter(ApiLoadGuard guard, ApiLoadSettings settings, ApiErrors errors, boolean api) {
        this.guard = guard;
        this.settings = settings;
        this.errors = errors;
        this.api = api;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String type = request.getContentType();
        boolean multipart = type != null && type.toLowerCase(Locale.ROOT).startsWith("multipart/form-data");
        boolean fileOperation = multipart || request.getServletPath().contains("/evidencias")
                || request.getRequestURI().contains("/evidencias");
        try (var lease = admit(request, fileOperation)) {
            int limit = multipart ? settings.multipartBytes() : settings.jsonBytes();
            if (request.getContentLengthLong() > limit) throw new BodyTooLarge();
            chain.doFilter(new LimitedRequest(request, limit), response);
        } catch (ApiLoadGuard.Rejected rejected) {
            response.setHeader("Retry-After", Long.toString(rejected.retryAfter));
            errors.write(request, response, new ApiErrors.Problem(rejected.status, rejected.code,
                    "Intente de nuevo mas tarde."));
        } catch (BodyTooLarge oversized) {
            errors.write(request, response, new ApiErrors.Problem(HttpStatus.CONTENT_TOO_LARGE,
                    "CARGA_DEMASIADO_GRANDE", "La carga supera el limite permitido."));
        }
    }

    private ApiLoadGuard.Lease admit(HttpServletRequest request, boolean fileOperation) {
        String address = request.getRemoteAddr();
        if (!api) return guard.enter(ANONYMOUS, address, fileOperation);
        String path = request.getServletPath();
        if ("POST".equals(request.getMethod())) {
            if (ApiSessionController.SESSION.equals(path)) return guard.enter(LOGIN, address, fileOperation);
            if (ApiRecoveryController.REQUEST.equals(path) || ApiRecoveryController.CONFIRM.equals(path)
                    || ApiInvitationController.ENROLL.equals(path)) return guard.enter(ANONYMOUS, address, fileOperation);
        }
        // Only the server's security context can identify an account. The next filter still checks revocation.
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof SessionIdentity identity) {
            return guard.enter(ACCOUNT, identity.getName(), fileOperation);
        }
        return guard.enter(ANONYMOUS, address, fileOperation);
    }

    private final class LimitedRequest extends HttpServletRequestWrapper {
        private final int limit;
        private ServletInputStream input;
        LimitedRequest(HttpServletRequest request, int limit) { super(request); this.limit = limit; }
        @Override public HttpSession getSession() { return getSession(true); }
        @Override public HttpSession getSession(boolean create) {
            var existing = super.getSession(false);
            if (existing != null || !create) return existing;
            var lease = guard.newSession();
            try {
                var session = super.getSession(true);
                session.setAttribute(SESSION_LEASE, lease);
                return session;
            } catch (RuntimeException failure) {
                lease.close();
                throw failure;
            }
        }
        @Override public ServletInputStream getInputStream() throws IOException {
            if (input == null) input = new LimitedInput(super.getInputStream(), limit);
            return input;
        }
        @Override public BufferedReader getReader() throws IOException {
            Charset charset = getCharacterEncoding() == null ? StandardCharsets.UTF_8
                    : Charset.forName(getCharacterEncoding());
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }
    }

    static final class BodyTooLarge extends IOException {}

    private static final class LimitedInput extends ServletInputStream {
        private final ServletInputStream source;
        private final int limit;
        private long count;
        LimitedInput(ServletInputStream source, int limit) { this.source = source; this.limit = limit; }
        private int checked(int read) throws BodyTooLarge {
            if (read > 0 && (count += read) > limit) throw new BodyTooLarge();
            return read;
        }
        @Override public int read() throws IOException {
            int value = source.read();
            if (value != -1) checked(1);
            return value;
        }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            return checked(source.read(bytes, offset, (int) Math.min(length, Math.max(1, limit - count + 1))));
        }
        @Override public boolean isFinished() { return source.isFinished(); }
        @Override public boolean isReady() { return source.isReady(); }
        @Override public void setReadListener(ReadListener listener) { source.setReadListener(listener); }
        @Override public void close() throws IOException { source.close(); }
    }
}
