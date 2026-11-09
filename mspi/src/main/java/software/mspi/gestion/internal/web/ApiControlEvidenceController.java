package software.mspi.gestion.internal.web;

import java.io.IOException;
import java.net.URI;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.util.UriComponentsBuilder;
import software.mspi.evidencias.api.ControlEvidence;
import software.mspi.evidencias.api.EvidenceFailure;
import software.mspi.evidencias.api.ResponseEvidence;
import software.mspi.identidad.api.SessionIdentity;

@RestController
@ConditionalOnProperty(name = {"mspi.tenancy.enabled", "mspi.evidence.enabled"}, havingValue = "true")
class ApiControlEvidenceController {
    static final String EVIDENCE = ApiControlImplementationController.VERSION + "/evidencias";
    static final String CONTENT = EVIDENCE + "/{evidenciaId}/archivo";

    private final ControlEvidence evidence;

    ApiControlEvidenceController(ControlEvidence evidence) {
        this.evidence = evidence;
    }

    @PostMapping(path = EVIDENCE, consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Upload> upload(@PathVariable UUID empresaId, @PathVariable UUID implementacionId, @PathVariable UUID versionId,
            @RequestParam @PositiveOrZero long revisionEsperada, @RequestParam(required = false) @Size(max = 1000) String descripcion,
            @RequestPart MultipartFile archivo, @AuthenticationPrincipal SessionIdentity actor, HttpServletRequest request) {
        var target = target(empresaId, implementacionId, versionId, actor, request);
        try (var stream = archivo.getInputStream()) {
            String description = descripcion == null || descripcion.isBlank() ? null : descripcion.strip();
            var metadata = evidence.upload(target, revisionEsperada, archivo.getOriginalFilename(), description, stream);
            URI location = UriComponentsBuilder.fromPath(CONTENT)
                    .buildAndExpand(empresaId, implementacionId, versionId, metadata.evidenciaId()).toUri();
            return ResponseEntity.created(location).cacheControl(CacheControl.noStore())
                    .body(new Upload(metadata, revisionEsperada + 1));
        } catch (IOException failure) {
            throw new EvidenceFailure(EvidenceFailure.Reason.INVALID_FILE);
        }
    }

    @GetMapping(path = EVIDENCE, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<ResponseEvidence.Page> list(@PathVariable UUID empresaId, @PathVariable UUID implementacionId,
            @PathVariable UUID versionId, @RequestParam(defaultValue = "0") @Min(0) int pagina,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int tamano,
            @AuthenticationPrincipal SessionIdentity actor, HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(evidence.list(target(empresaId, implementacionId, versionId, actor, request), pagina, tamano));
    }

    @GetMapping(path = CONTENT, produces = {"application/pdf", "image/png", "image/jpeg"})
    ResponseEntity<byte[]> download(@PathVariable UUID empresaId, @PathVariable UUID implementacionId,
            @PathVariable UUID versionId, @PathVariable UUID evidenciaId,
            @AuthenticationPrincipal SessionIdentity actor, HttpServletRequest request) {
        return ApiEvidenceController.file(evidence.download(target(empresaId, implementacionId, versionId, actor, request),
                evidenciaId));
    }

    private static ControlEvidence.Target target(UUID company, UUID implementation, UUID version, SessionIdentity actor,
                                                  HttpServletRequest request) {
        return new ControlEvidence.Target(ApiControlImplementationController.scope(company, actor, request), implementation, version);
    }

    record Upload(ResponseEvidence.Metadata evidencia, long revisionImplementacion) {}
}
