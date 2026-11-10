package software.mspi.gestion.internal.web;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import software.mspi.auditoria.api.ActivityLog;
import software.mspi.auditoria.api.ActivityLog.Activity;
import software.mspi.auditoria.api.ActivityLog.Filters;
import software.mspi.auditoria.api.ActivityLog.Source;
import software.mspi.auditoria.api.ActivityLog.Window;
import software.mspi.empresas.api.CompanyAdministration;
import software.mspi.empresas.api.CompanyPermission;
import software.mspi.empresas.api.TenantUnitOfWork;
import software.mspi.identidad.api.SessionIdentity;

@RestController
@ConditionalOnProperty(name = "mspi.tenancy.enabled", havingValue = "true")
class ApiCompanyActivityController {
    static final String ACTIVITY = "/api/v1/empresas/{empresaId}/actividad";
    private static final Instant FIRST_INSTANT = Instant.parse("0001-01-01T00:00:00Z");
    private static final Instant END_OF_RANGE = Instant.parse("+10000-01-01T00:00:00Z");

    // Decimal identifiers compare numerically by length, then by value.
    private static final Comparator<Activity> RECENT_FIRST = Comparator.comparing(Activity::registradoEn)
            .thenComparing(Activity::origen)
            .thenComparingInt(event -> event.eventoId().length())
            .thenComparing(Activity::eventoId).reversed();

    private final TenantUnitOfWork work;
    private final ActivityLog events;
    private final CompanyAdministration administration;

    ApiCompanyActivityController(TenantUnitOfWork work, ActivityLog events, CompanyAdministration administration) {
        this.work = work;
        this.events = events;
        this.administration = administration;
    }

    @GetMapping(path = ACTIVITY, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Page> activity(@PathVariable UUID empresaId, @AuthenticationPrincipal SessionIdentity actor,
                                  @Valid @ModelAttribute Query query,
                                  @RequestParam(defaultValue = "20") @Min(1) @Max(100) int tamano) {
        if (actor == null) throw new AccessDeniedException("Acceso empresarial no autorizado.");
        var cursor = Cursor.decode(query.cursor());
        var filters = new Filters(query.desde(), query.hasta(), query.actorId(), query.accion(),
                query.tipoObjeto(), query.objetoId());
        var page = work.execute(actor.usuarioId(), empresaId, CompanyPermission.READ_ACTIVITY, () -> {
            var admin = administration.activity(empresaId, filters, window(cursor, Source.ADMINISTRATIVA), tamano + 1);
            var business = events.companyActivity(empresaId, filters, window(cursor, Source.OPERATIVA), tamano + 1);
            var rows = Stream.concat(admin.stream(), business.stream()).sorted(RECENT_FIRST).limit(tamano + 1L).toList();
            boolean hasMore = rows.size() > tamano;
            return new Page(hasMore ? List.copyOf(rows.subList(0, tamano)) : rows,
                    hasMore ? Cursor.encode(rows.get(tamano - 1)) : null);
        });
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(page);
    }

    private static Window window(Cursor cursor, Source source) {
        if (cursor == null) return new Window(null, false, null);
        return new Window(cursor.time(), source.compareTo(cursor.source()) < 0,
                source == cursor.source() ? cursor.id() : null);
    }

    record Page(List<Activity> elementos, String siguienteCursor) {}

    private static boolean supported(Instant value) {
        return value == null || (!value.isBefore(FIRST_INSTANT) && value.isBefore(END_OF_RANGE));
    }

    record Query(Instant desde, Instant hasta, UUID actorId,
                 @Pattern(regexp = "[A-Z][A-Z0-9_]{0,79}") String accion,
                 @Pattern(regexp = "[A-Z][A-Z0-9_]{0,79}") String tipoObjeto, UUID objetoId,
                 @Size(min = 1, max = 160) String cursor) {
        @AssertTrue(message = "Use instantes entre los años 0001 y 9999, con desde anterior a hasta.")
        public boolean isPeriodoValido() {
            return supported(desde) && supported(hasta) && (desde == null || hasta == null || desde.isBefore(hasta));
        }

        @AssertTrue(message = "El cursor de actividad no es válido.")
        public boolean isCursorValido() {
            return cursor == null || Cursor.decode(cursor) != null;
        }
    }

    private record Cursor(Instant time, Source source, String id) {
        static String encode(Activity event) {
            String position = event.registradoEn() + "|" + event.origen() + "|" + event.eventoId();
            return Base64.getUrlEncoder().withoutPadding().encodeToString(position.getBytes(StandardCharsets.UTF_8));
        }

        static Cursor decode(String token) {
            if (token == null || token.length() > 160) return null;
            try {
                String[] parts = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8).split("\\|", -1);
                if (parts.length != 3) return null;
                var time = Instant.parse(parts[0]);
                var source = Source.valueOf(parts[1]);
                var id = parts[2];
                boolean validId = switch (source) {
                    case ADMINISTRATIVA -> Long.parseLong(id) > 0;
                    case OPERATIVA -> UUID.fromString(id).toString().equals(id);
                };
                return validId && supported(time) ? new Cursor(time, source, id) : null;
            } catch (IllegalArgumentException | DateTimeParseException invalid) {
                return null;
            }
        }
    }
}
