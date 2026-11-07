package software.mspi.gestion.internal.web;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Component;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.ModelAndView;
import software.mspi.empresas.api.InvitationFailure;
import software.mspi.identidad.api.LoginLimitException;
import tools.jackson.databind.exc.UnrecognizedPropertyException;
import tools.jackson.databind.json.JsonMapper;

@Component
final class ApiErrors {

    private static final Logger LOG = LoggerFactory.getLogger(ApiErrors.class);
    private static final Pattern FIELD_PATH =
            Pattern.compile("[a-zA-Z_][a-zA-Z0-9_]*(?:\\[[0-9]+\\]|\\.[a-zA-Z_][a-zA-Z0-9_]*)*");

    private final JsonMapper mapper;

    ApiErrors(JsonMapper mapper) {
        this.mapper = mapper;
    }

    ModelAndView resolve(HttpServletRequest request, HttpServletResponse response, Object handler, Exception failure) {
        if (ApiCorrelationFilter.id(request) == null || response.isCommitted()) {
            return null;
        }

        Problem problem = describe(failure);
        if (problem.status().is5xxServerError()) {
            LOG.error("Fallo de API {} correlationId={} exceptionType={}", problem.code(),
                    ApiCorrelationFilter.id(request), failure.getClass().getName());
        }
        if (failure instanceof ErrorResponse error) {
            copyProtocolHeader(error, response, HttpHeaders.ALLOW);
            copyProtocolHeader(error, response, HttpHeaders.ACCEPT);
            copyProtocolHeader(error, response, "Accept-Patch");
        }
        try {
            write(request, response, problem);
        } catch (IOException disconnected) {
            LOG.debug("No se pudo escribir la respuesta de API correlationId={}", ApiCorrelationFilter.id(request));
        }
        return new ModelAndView();
    }

    void write(HttpServletRequest request, HttpServletResponse response, Problem problem) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        UUID id = ApiCorrelationFilter.id(request);
        if (id == null) {
            id = UUID.randomUUID();
        }
        response.resetBuffer();
        response.setStatus(problem.status().value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader(ApiCorrelationFilter.HEADER, id.toString());
        if (!"HEAD".equals(request.getMethod())) {
            mapper.writeValue(response.getOutputStream(), new Body("about:blank", problem.status().getReasonPhrase(),
                    problem.status().value(), problem.detail(), "urn:uuid:" + id, problem.code(), id, problem.fields()));
        }
    }

    private static Problem describe(Exception failure) {
        if (failure instanceof MethodArgumentNotValidException invalid) {
            var binding = invalid.getBindingResult();
            var fields = binding.getFieldErrors().stream().map(error -> invalidField(error.getField())).distinct().toList();
            return validation(fields.isEmpty() ? List.of(invalidField("body")) : fields);
        }
        if (failure instanceof HandlerMethodValidationException invalid && !invalid.isForReturnValue()) {
            return validation(invalid.getParameterValidationResults().stream()
                    .map(result -> invalidField(result.getMethodParameter().getParameterName())).distinct().toList());
        }
        if (failure instanceof MissingServletRequestParameterException missing) {
            return validation(List.of(new Field(missing.getParameterName(), "REQUERIDO", "Indique el parámetro.")));
        }
        if (failure instanceof MethodArgumentTypeMismatchException mismatch) {
            return validation(List.of(invalidField(mismatch.getName())));
        }
        if (failure instanceof HttpMessageNotReadableException unreadable) {
            String code = unreadable.getCause() instanceof UnrecognizedPropertyException
                    ? "CAMPO_NO_PERMITIDO" : "JSON_INVALIDO";
            return new Problem(HttpStatus.BAD_REQUEST, code, "El cuerpo no coincide con el contrato JSON.",
                    List.of(new Field("body", code, "Revise los campos y tipos admitidos.")));
        }
        if (failure instanceof OptimisticLockingFailureException) {
            return new Problem(HttpStatus.CONFLICT, "REVISION_DESACTUALIZADA",
                    "Consulte la revisión vigente antes de reintentar.");
        }
        if (failure instanceof BadCredentialsException) {
            return new Problem(HttpStatus.UNAUTHORIZED, "CREDENCIALES_INVALIDAS", "Credenciales inválidas.");
        }
        if (failure instanceof LoginLimitException) {
            return new Problem(HttpStatus.TOO_MANY_REQUESTS, "DEMASIADOS_INTENTOS", "Intente de nuevo más tarde.");
        }
        if (failure instanceof InvitationFailure invitation) {
            return switch (invitation.reason()) {
                case UNAVAILABLE -> new Problem(HttpStatus.CONFLICT, "INVITACION_NO_DISPONIBLE",
                        "La invitación no está disponible para esta operación.");
                case DELIVERY -> new Problem(HttpStatus.SERVICE_UNAVAILABLE, "ENVIO_NO_DISPONIBLE",
                        "No se pudo enviar la invitación. Intente de nuevo más tarde.");
                case PASSWORD_LENGTH -> validation(List.of(new Field("contrasena", "VALOR_INVALIDO",
                        "La contraseña no alcanza la longitud mínima configurada.")));
            };
        }
        if (failure instanceof AccessDeniedException) {
            return Problem.forStatus(HttpStatus.FORBIDDEN);
        }
        if (failure instanceof ErrorResponse error) {
            HttpStatus status = HttpStatus.resolve(error.getStatusCode().value());
            if (status != null && status.isError()) {
                return Problem.forStatus(status);
            }
        }
        return Problem.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    private static void copyProtocolHeader(ErrorResponse error, HttpServletResponse response, String name) {
        List<String> values = error.getHeaders().get(name);
        if (values != null) {
            values.forEach(value -> response.addHeader(name, value));
        }
    }

    private static Problem validation(List<Field> fields) {
        return new Problem(HttpStatus.BAD_REQUEST, "VALIDACION", "Revise los campos indicados.", fields);
    }

    private static Field invalidField(String name) {
        String field = name != null && name.length() <= 128 && FIELD_PATH.matcher(name).matches() ? name : "body";
        return new Field(field, "VALOR_INVALIDO", "Revise el valor del campo.");
    }

    record Problem(HttpStatus status, String code, String detail, List<Field> fields) {

        Problem(HttpStatus status, String code, String detail) {
            this(status, code, detail, List.of());
        }

        static Problem forStatus(HttpStatus status) {
            return switch (status) {
                case BAD_REQUEST -> new Problem(status, "SOLICITUD_INVALIDA", "Revise los datos de la solicitud.");
                case UNAUTHORIZED -> new Problem(status, "NO_AUTENTICADO", "Se requiere una sesión válida.");
                case FORBIDDEN -> new Problem(status, "ACCESO_DENEGADO", "La operación no está autorizada.");
                case NOT_FOUND -> new Problem(status, "NO_ENCONTRADO", "El recurso no está disponible.");
                case METHOD_NOT_ALLOWED -> new Problem(status, "METODO_NO_PERMITIDO", "Revise el método de la operación.");
                case NOT_ACCEPTABLE -> new Problem(status, "RESPUESTA_NO_ACEPTABLE", "Revise los tipos de respuesta admitidos.");
                case CONFLICT -> new Problem(status, "CONFLICTO", "La operación entra en conflicto con el estado vigente.");
                case UNSUPPORTED_MEDIA_TYPE -> new Problem(status, "TIPO_CONTENIDO_NO_ADMITIDO", "Revise el tipo del cuerpo.");
                default -> status.is5xxServerError()
                        ? new Problem(status, "ERROR_INTERNO", "No se pudo completar la operación.")
                        : new Problem(status, "SOLICITUD_RECHAZADA", "La solicitud no se pudo procesar.");
            };
        }
    }

    record Field(String field, String code, String message) {}

    record Body(String type, String title, int status, String detail, String instance, String code,
                UUID correlationId, List<Field> fields) {}
}
