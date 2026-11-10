package software.mspi.gestion.internal.web;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import software.mspi.evidencias.api.EvidenceFailure;
import software.mspi.evidencias.api.ResponseEvidence;
import software.mspi.identidad.api.SessionIdentity;

@RestController
@ConditionalOnProperty(name = {"mspi.tenancy.enabled", "mspi.evidence.enabled"}, havingValue = "true")
class ApiEvidenceController {
    static final String EVIDENCE = ApiEvaluationController.RESPONSE + "/evidencias";
    static final String CONTENT = EVIDENCE + "/{evidenciaId}/archivo";
    static final String ACTION_CONTEXT_CONTENT =
            ApiImprovementActionController.ACTION + "/evidencias-contexto/{evidenciaId}/archivo";

    private final ResponseEvidence evidence;

    ApiEvidenceController(ResponseEvidence evidence) {
        this.evidence = evidence;
    }

    @PostMapping(path = EVIDENCE, consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Upload> upload(@PathVariable UUID empresaId, @PathVariable UUID evaluacionId,
            @PathVariable UUID respuestaId, @RequestParam @PositiveOrZero long revisionEsperada,
            @RequestParam(required = false) @Size(max = 1000) String descripcion,
            @RequestPart MultipartFile archivo, @AuthenticationPrincipal SessionIdentity actor, HttpServletRequest request) {
        var target = target(empresaId, evaluacionId, respuestaId, actor, request);
        try (var stream = archivo.getInputStream()) {
            String description = descripcion == null || descripcion.isBlank() ? null : descripcion.strip();
            var metadata = evidence.upload(target, revisionEsperada, archivo.getOriginalFilename(), description, stream);
            String location = "/api/v1/empresas/%s/evaluaciones/%s/respuestas/%s/evidencias/%s/archivo"
                    .formatted(empresaId, evaluacionId, respuestaId, metadata.evidenciaId());
            return ResponseEntity.created(URI.create(location)).cacheControl(CacheControl.noStore())
                    .body(new Upload(metadata, revisionEsperada + 1));
        } catch (IOException failure) {
            throw new EvidenceFailure(EvidenceFailure.Reason.INVALID_FILE);
        }
    }

    @GetMapping(path = EVIDENCE, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<ResponseEvidence.Page> list(@PathVariable UUID empresaId, @PathVariable UUID evaluacionId,
            @PathVariable UUID respuestaId, @RequestParam(defaultValue = "0") @Min(0) int pagina,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int tamano,
            @AuthenticationPrincipal SessionIdentity actor, HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(evidence.list(target(empresaId, evaluacionId, respuestaId, actor, request), pagina, tamano));
    }

    @GetMapping(path = CONTENT, produces = {"application/pdf", "image/png", "image/jpeg"})
    ResponseEntity<byte[]> download(@PathVariable UUID empresaId, @PathVariable UUID evaluacionId,
            @PathVariable UUID respuestaId, @PathVariable UUID evidenciaId,
            @AuthenticationPrincipal SessionIdentity actor, HttpServletRequest request) {
        return file(evidence.download(target(empresaId, evaluacionId, respuestaId, actor, request), evidenciaId));
    }

    @GetMapping(path = ACTION_CONTEXT_CONTENT, produces = {"application/pdf", "image/png", "image/jpeg"})
    ResponseEntity<byte[]> actionContext(@PathVariable UUID empresaId, @PathVariable UUID accionId,
            @PathVariable UUID evidenciaId, @AuthenticationPrincipal SessionIdentity actor, HttpServletRequest request) {
        if (actor == null) throw new AccessDeniedException("Acceso empresarial no autorizado.");
        var target = new ResponseEvidence.ActionContextTarget(actor.usuarioId(), empresaId, accionId, evidenciaId,
                ApiCorrelationFilter.id(request));
        return file(evidence.downloadActionContext(target));
    }

    static ResponseEntity<byte[]> file(ResponseEvidence.Download download) {
        var metadata = download.metadata();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .contentType(MediaType.parseMediaType(metadata.tipoDetectado()))
                .contentLength(metadata.tamanoBytes())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(metadata.nombreOriginal(), StandardCharsets.UTF_8).build().toString())
                .header("Content-Security-Policy", "sandbox; default-src 'none'")
                .body(download.content());
    }

    private static ResponseEvidence.Target target(UUID company, UUID evaluation, UUID response,
            SessionIdentity actor, HttpServletRequest request) {
        if (actor == null) throw new AccessDeniedException("Acceso empresarial no autorizado.");
        return new ResponseEvidence.Target(actor.usuarioId(), company, evaluation, response, ApiCorrelationFilter.id(request));
    }

    record Upload(ResponseEvidence.Metadata evidencia, long revisionEvaluacion) {}
}
