package software.mspi.gestion.internal.web;

import java.io.IOException;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import software.mspi.empresas.api.DatabaseCapacityExceeded;
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
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.ModelAndView;
import software.mspi.activos.api.CatalogFailure;
import software.mspi.activos.api.ClassificationFailure;
import software.mspi.catalogos.api.ReferenceNotFound;
import software.mspi.empresas.api.CompanyAdministrationFailure;
import software.mspi.empresas.api.InvitationFailure;
import software.mspi.empresas.api.PlatformFailure;
import software.mspi.evaluaciones.api.EvaluationBlocked;
import software.mspi.evaluaciones.api.EvaluationFailure;
import software.mspi.evidencias.api.EvidenceFailure;
import software.mspi.identidad.api.LoginLimitException;
import software.mspi.identidad.api.RecoveryFailure;
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
        if (problem.status().is5xxServerError() && !List.of("CAPACIDAD_AGOTADA", "BASE_OCUPADA").contains(problem.code())) {
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
        if (("CAPACIDAD_AGOTADA".equals(problem.code()) || "DEMASIADAS_SOLICITUDES".equals(problem.code()) || "BASE_OCUPADA".equals(problem.code()))
                && !response.containsHeader("Retry-After")) response.setHeader("Retry-After", "1");
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
        Throwable cause = failure;
        for (int depth = 0; cause != null && depth < 12; depth++, cause = cause.getCause()) {
            if (cause instanceof DatabaseCapacityExceeded || cause instanceof SQLTransientConnectionException
                    || cause instanceof SQLException sql && sql.getSQLState() != null && List.of("57014", "55P03", "53300", "53400", "25P03", "25P04").contains(sql.getSQLState())) {
                return new Problem(HttpStatus.SERVICE_UNAVAILABLE, "BASE_OCUPADA", "La operacion excedio el presupuesto de base de datos. Reintente mas tarde.");
            }
            if (cause instanceof ApiLoadFilter.BodyTooLarge) return oversizedEvidence();
            if (cause instanceof ApiLoadGuard.Rejected rejected) {
                return new Problem(rejected.status, rejected.code, "Intente de nuevo mas tarde.");
            }
        }
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
        if (failure instanceof MissingRequestHeaderException missing) {
            return validation(List.of(new Field(missing.getHeaderName(), "REQUERIDO", "Indique la cabecera.")));
        }
        if (failure instanceof MissingServletRequestPartException missing) {
            return validation(List.of(new Field(missing.getRequestPartName(), "REQUERIDO", "Indique la parte del formulario.")));
        }
        if (failure instanceof MaxUploadSizeExceededException) {
            return oversizedEvidence();
        }
        if (failure instanceof MultipartException) {
            return Problem.forStatus(HttpStatus.BAD_REQUEST);
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
        if (failure instanceof EvaluationBlocked blocked) {
            var issues = blocked.issues().stream().map(issue -> new Field(
                    issue.respuestaId() == null ? issue.campo() : "respuestas." + issue.respuestaId() + "." + issue.campo(),
                    issue.codigo(), issue.detalle())).toList();
            return new Problem(HttpStatus.CONFLICT, "EVALUACION_INCOMPLETA", blocked.getMessage(), issues);
        }
        if (failure instanceof EvaluationFailure evaluation) {
            return switch (evaluation.reason()) {
                case MISSING -> Problem.forStatus(HttpStatus.NOT_FOUND);
                case NOT_EDITABLE -> new Problem(HttpStatus.CONFLICT, "EVALUACION_NO_EDITABLE",
                        "Solo una evaluación en borrador admite cambios.");
                case RESPONSIBLE -> new Problem(HttpStatus.CONFLICT, "RESPONSABLE_NO_DISPONIBLE",
                        "El responsable debe tener una membresía activa de la empresa con rol AE o GS.");
                case VERSION -> new Problem(HttpStatus.CONFLICT, "VERSION_NO_DISPONIBLE",
                        "Seleccione versiones publicadas, compatibles y disponibles para nuevos usos.");
                case INVALID_ANSWER -> validation(evaluation.fields().stream().map(ApiErrors::invalidField).toList());
                case STATE -> new Problem(HttpStatus.CONFLICT, "ESTADO_EVALUACION_INCOMPATIBLE",
                        "La evaluación no admite esta transición en su estado vigente.");
                case POLICY -> new Problem(HttpStatus.CONFLICT, "POLITICA_EVALUACION_NO_DISPONIBLE",
                        "La versión del catálogo y la metodología requieren una política de cierre aprobada.");
                case NOT_INHERITED -> new Problem(HttpStatus.CONFLICT, "RESPUESTA_HEREDADA_NO_PENDIENTE",
                        "La respuesta no tiene una confirmación heredada pendiente.");
                case CORRECTION_EXISTS -> new Problem(HttpStatus.CONFLICT, "CORRECCION_EXISTENTE",
                        "La evaluación ya tiene una corrección vinculada.");
            };
        }
        if (failure instanceof EvidenceFailure evidence) {
            return switch (evidence.reason()) {
                case INVALID_FILE -> validation(List.of(invalidField("archivo")));
                case TOO_LARGE -> oversizedEvidence();
                case MISSING -> Problem.forStatus(HttpStatus.NOT_FOUND);
                case QUOTA -> new Problem(HttpStatus.CONFLICT, "CUOTA_EVIDENCIAS_AGOTADA",
                        "La empresa no dispone de cuota para este archivo.");
                case QUOTA_NOT_CONFIGURED -> new Problem(HttpStatus.SERVICE_UNAVAILABLE, "CUOTA_EVIDENCIAS_NO_CONFIGURADA",
                        "La empresa requiere una cuota de evidencias configurada.");
                case STORAGE -> new Problem(HttpStatus.SERVICE_UNAVAILABLE, "ALMACENAMIENTO_EVIDENCIAS_NO_DISPONIBLE",
                        "No se pudo completar la operación con el archivo.");
            };
        }
        if (failure instanceof ReferenceNotFound) {
            return Problem.forStatus(HttpStatus.NOT_FOUND);
        }
        if (failure instanceof ClassificationFailure classification) {
            var fields = classification.fields().stream()
                    .map(field -> invalidField("motivoRevision".equals(field) ? field : "entradas." + field)).toList();
            return switch (classification.reason()) {
                case INVALID -> validation(fields);
                case INCOMPLETE -> new Problem(HttpStatus.CONFLICT, "CLASIFICACION_INCOMPLETA",
                        "Complete los campos requeridos antes de confirmar.", fields);
                case DRAFT_EXISTS -> new Problem(HttpStatus.CONFLICT, "CLASIFICACION_PENDIENTE_EXISTENTE",
                        "El activo ya tiene una valoración pendiente.");
                case CONFIRMED -> new Problem(HttpStatus.CONFLICT, "VALORACION_CONFIRMADA",
                        "Cree una nueva versión para revisar esta valoración.");
            };
        }
        if (failure instanceof CatalogFailure catalog) {
            return switch (catalog.reason()) {
                case MISSING -> Problem.forStatus(HttpStatus.NOT_FOUND);
                case REFERENCE -> new Problem(HttpStatus.CONFLICT, "REFERENCIA_NO_DISPONIBLE",
                        "La referencia no está disponible para esta operación.");
                case ARCHIVED -> new Problem(HttpStatus.CONFLICT, "REGISTRO_ARCHIVADO",
                        "El registro archivado no admite cambios.");
                case DUPLICATE_CODE -> new Problem(HttpStatus.CONFLICT, "CODIGO_ACTIVO_DUPLICADO",
                        "El código ya pertenece a un activo de la empresa.");
                case INCOMPATIBLE_STATE -> new Problem(HttpStatus.CONFLICT, "ESTADO_INCOMPATIBLE",
                        "El estado vigente no permite la operación.");
            };
        }
        if (failure instanceof RecoveryFailure recovery) {
            return switch (recovery.reason()) {
                case UNAVAILABLE -> new Problem(HttpStatus.CONFLICT, "RECUPERACION_NO_DISPONIBLE",
                        "El comprobante de recuperación no está disponible.");
                case PASSWORD_POLICY -> validation(List.of(new Field("contrasena", "VALOR_INVALIDO",
                        "Elija otra contraseña que cumpla la longitud mínima y no sea común o comprometida.")));
            };
        }
        if (failure instanceof InvitationFailure invitation) {
            return switch (invitation.reason()) {
                case UNAVAILABLE -> new Problem(HttpStatus.CONFLICT, "INVITACION_NO_DISPONIBLE",
                        "La invitación no está disponible para esta operación.");
                case DELIVERY -> new Problem(HttpStatus.SERVICE_UNAVAILABLE, "ENVIO_NO_DISPONIBLE",
                        "No se pudo enviar la invitación. Intente de nuevo más tarde.");
                case PASSWORD_POLICY -> validation(List.of(new Field("contrasena", "VALOR_INVALIDO",
                        "Elija otra contraseña que cumpla la longitud mínima y no sea común o comprometida.")));
            };
        }
        if (failure instanceof CompanyAdministrationFailure administration) {
            return switch (administration.reason()) {
                case MISSING -> Problem.forStatus(HttpStatus.NOT_FOUND);
                case LAST_ADMINISTRATOR -> new Problem(HttpStatus.CONFLICT, "ULTIMO_AE",
                        "La empresa debe conservar al menos un administrador activo.");
                case REVOKED -> new Problem(HttpStatus.CONFLICT, "MEMBRESIA_REVOCADA",
                        "La membresía revocada ya no se puede modificar.");
                case INVALID_ZONE -> validation(List.of(new Field("zonaHoraria", "VALOR_INVALIDO",
                        "Use una zona horaria reconocida por el sistema.")));
            };
        }
        if (failure instanceof PlatformFailure platform) {
            return switch (platform.reason()) {
                case CONFLICT -> new Problem(HttpStatus.CONFLICT, "ESTADO_O_REVISION_INCOMPATIBLE",
                        "Consulte el estado y la revisión vigentes de la empresa.");
                case MISSING -> Problem.forStatus(HttpStatus.NOT_FOUND);
                case INCOMPATIBLE -> new Problem(HttpStatus.CONFLICT, "ESTRUCTURA_NO_COMPATIBLE",
                        "La empresa necesita una verificación técnica satisfactoria.");
                case MAIL_DISABLED -> new Problem(HttpStatus.SERVICE_UNAVAILABLE, "ENVIO_NO_DISPONIBLE",
                        "La emisión de invitaciones requiere configuración.");
                case INVALID_ZONE -> validation(List.of(new Field("zonaHoraria", "VALOR_INVALIDO",
                        "Use una zona horaria reconocida por el sistema.")));
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

    private static Problem oversizedEvidence() {
        return new Problem(HttpStatus.CONTENT_TOO_LARGE, "CARGA_DEMASIADO_GRANDE", "La carga supera el límite permitido.");
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
