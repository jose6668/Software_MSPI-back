package software.mspi.gestion.internal.web;

import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import software.mspi.catalogos.api.ReferenceCatalogs;
import software.mspi.catalogos.api.ReferenceCatalogs.CatalogVersion;
import software.mspi.catalogos.api.ReferenceCatalogs.Element;
import software.mspi.catalogos.api.ReferenceCatalogs.ElementFilter;
import software.mspi.catalogos.api.ReferenceCatalogs.ElementSummary;
import software.mspi.catalogos.api.ReferenceCatalogs.MethodVersion;
import software.mspi.catalogos.api.ReferenceCatalogs.Page;
import software.mspi.catalogos.api.ReferenceCatalogs.Type;

@RestController
class ApiReferenceCatalogController {

    private static final String ROOT = "/api/v1/empresas/{empresaId}";
    static final String CATALOGS = ROOT + "/catalogos";
    static final String CATALOG = CATALOGS + "/{versionId}";
    static final String ELEMENTS = CATALOG + "/elementos";
    static final String ELEMENT = ELEMENTS + "/{elementoId}";
    static final String METHODS = ROOT + "/metodologias";
    static final String METHOD = METHODS + "/{versionId}";

    private final ReferenceCatalogs reference;

    ApiReferenceCatalogController(ReferenceCatalogs reference) {
        this.reference = reference;
    }

    @GetMapping(path = CATALOGS, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Page<CatalogVersion>> catalogs(@PathVariable UUID empresaId,
            @RequestParam(required = false) @Size(max = 80) String codigo,
            @RequestParam(required = false) Boolean disponibleNuevos,
            @RequestParam(defaultValue = "0") @Min(0) int pagina, @RequestParam(defaultValue = "20") @Min(1) @Max(100) int tamano) {
        return view(reference.catalogs(empresaId, codigo, disponibleNuevos, pagina, tamano));
    }

    @GetMapping(path = CATALOG, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<CatalogVersion> catalog(@PathVariable UUID empresaId, @PathVariable UUID versionId) {
        return view(reference.catalog(empresaId, versionId));
    }

    @GetMapping(path = ELEMENTS, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Page<ElementSummary>> elements(@PathVariable UUID empresaId, @PathVariable UUID versionId,
            @RequestParam(required = false) Type tipo, @RequestParam(required = false) @Size(max = 80) String grupo,
            @RequestParam(required = false) @Size(max = 80) String codigo,
            @RequestParam(required = false) @Size(max = 250) String texto,
            @RequestParam(defaultValue = "0") @Min(0) int pagina, @RequestParam(defaultValue = "20") @Min(1) @Max(100) int tamano) {
        return view(reference.elements(empresaId, versionId, new ElementFilter(tipo, grupo, codigo, texto), pagina, tamano));
    }

    @GetMapping(path = ELEMENT, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Element> element(@PathVariable UUID empresaId, @PathVariable UUID versionId, @PathVariable UUID elementoId) {
        return view(reference.element(empresaId, versionId, elementoId));
    }

    @GetMapping(path = METHODS, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Page<MethodVersion>> methodologies(@PathVariable UUID empresaId,
            @RequestParam(required = false) @Size(max = 80) String codigo,
            @RequestParam(required = false) Boolean disponibleNuevos,
            @RequestParam(defaultValue = "0") @Min(0) int pagina, @RequestParam(defaultValue = "20") @Min(1) @Max(100) int tamano) {
        return view(reference.methodologies(empresaId, codigo, disponibleNuevos, pagina, tamano));
    }

    @GetMapping(path = METHOD, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<MethodVersion> methodology(@PathVariable UUID empresaId, @PathVariable UUID versionId) {
        return view(reference.methodology(empresaId, versionId));
    }

    private static <T> ResponseEntity<T> view(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
