package software.mspi.gestion.internal.web;

import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import software.mspi.empresas.api.CompanyAccess;
import software.mspi.empresas.api.CompanyAccess.CompanyMembership;
import software.mspi.empresas.api.CompanyAccess.CompanyPage;
import software.mspi.empresas.api.CompanyPermission;

@RestController
class ApiCompanyController {

    static final String MINE = "/api/v1/mis-empresas";
    static final String CONTEXT = "/api/v1/empresas/{empresaId}/contexto";

    private final CompanyAccess access;

    ApiCompanyController(CompanyAccess access) {
        this.access = access;
    }

    @GetMapping(path = MINE, produces = MediaType.APPLICATION_JSON_VALUE)
    CompanyPage list(@RequestParam(defaultValue = "0") @Min(0) int pagina,
                     @RequestParam(defaultValue = "20") @Min(1) @Max(100) int tamano) {
        return access.listMine(pagina, tamano);
    }

    @GetMapping(path = CONTEXT, produces = MediaType.APPLICATION_JSON_VALUE)
    CompanyMembership context(@PathVariable UUID empresaId) {
        return access.require(empresaId, CompanyPermission.READ_CONTEXT);
    }
}
