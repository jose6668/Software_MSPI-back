package software.mspi.catalogos.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import tools.jackson.databind.JsonNode;

public interface ReferenceCatalogs {

    Page<CatalogVersion> catalogs(UUID company, String code, Boolean available, int page, int size);
    CatalogVersion catalog(UUID company, UUID version);
    Page<ElementSummary> elements(UUID company, UUID version, ElementFilter filter, int page, int size);
    Element element(UUID company, UUID version, UUID element);
    Page<MethodVersion> methodologies(UUID company, String code, Boolean available, int page, int size);
    MethodVersion methodology(UUID company, UUID version);

    enum Type { CONTROL, CLAUSULA }

    record Page<T>(List<T> elementos, int pagina, int tamano, boolean hayMas) {}

    record CatalogVersion(UUID versionId, String codigo, String version, String nombre, String origen,
                          String huellaOrigen, Instant publicadaEn, boolean disponibleNuevos) {}

    record ElementFilter(Type tipo, String grupo, String codigo, String texto) {}

    record ElementSummary(UUID elementoId, UUID versionId, Type tipo, String codigo, String titulo,
                          String grupo, String referenciaOrigen) {}

    record Element(UUID elementoId, UUID versionId, Type tipo, String codigo, String titulo,
                   String descripcion, String grupo, String referenciaOrigen) {}

    record MethodVersion(UUID versionId, String codigo, String version, String identificador, String nombre,
                         String fundamento, String origen, JsonNode reglas, LocalDate aprobadaEn, String aprobadaPor,
                         boolean disponibleNuevos) {}
}
