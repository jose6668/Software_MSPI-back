package software.mspi.catalogos.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import software.mspi.catalogos.api.ReferenceCatalogs;
import software.mspi.catalogos.api.ReferenceNotFound;
import software.mspi.empresas.api.CompanyAccess;
import software.mspi.empresas.api.CompanyPermission;
import tools.jackson.databind.json.JsonMapper;

@Service
final class JdbcReferenceCatalogs implements ReferenceCatalogs {

    private static final String CATALOGS = "SELECT * FROM comun.catalogos_publicados";
    private static final String METHODS = "SELECT * FROM comun.metodologias_publicadas";
    private static final String ITEMS = " FROM comun.elementos_catalogo_publicados WHERE version_catalogo_id = :version";

    private final JdbcClient jdbc;
    private final CompanyAccess access;
    private final JsonMapper mapper;

    JdbcReferenceCatalogs(JdbcClient jdbc, CompanyAccess access, JsonMapper mapper) {
        this.jdbc = jdbc;
        this.access = access;
        this.mapper = mapper;
    }

    @Override
    public Page<CatalogVersion> catalogs(UUID company, String code, Boolean available, int page, int size) {
        requireAccess(company);
        return versions(CATALOGS, code, available, page, size, JdbcReferenceCatalogs::catalogVersion);
    }

    @Override
    public CatalogVersion catalog(UUID company, UUID version) {
        requireAccess(company);
        return version(CATALOGS, version, JdbcReferenceCatalogs::catalogVersion);
    }

    @Override
    public Page<ElementSummary> elements(UUID company, UUID version, ElementFilter filter, int page, int size) {
        catalog(company, version);
        var params = new HashMap<String, Object>();
        params.put("version", version);
        var sql = new StringBuilder("SELECT id, version_catalogo_id, tipo, codigo, titulo, grupo, referencia_origen")
                .append(ITEMS);
        equal(sql, params, "tipo", filter.tipo() == null ? null : filter.tipo().name());
        equal(sql, params, "grupo", filter.grupo());
        equal(sql, params, "codigo", filter.codigo());
        if (filter.texto() != null && !filter.texto().isBlank()) {
            sql.append(" AND (codigo ILIKE :search ESCAPE '!' OR titulo ILIKE :search ESCAPE '!'"
                    + " OR descripcion ILIKE :search ESCAPE '!')");
            params.put("search", "%" + filter.texto().strip().replace("!", "!!").replace("%", "!%")
                    .replace("_", "!_") + "%");
        }
        var rows = pageQuery(sql.append(" ORDER BY tipo, codigo, id").toString(), params, page, size)
                .query(JdbcReferenceCatalogs::summary).list();
        return page(rows, page, size);
    }

    @Override
    public Element element(UUID company, UUID version, UUID element) {
        requireAccess(company);
        return jdbc.sql("SELECT *" + ITEMS + " AND id = :element").param("version", version).param("element", element)
                .query(JdbcReferenceCatalogs::detail).optional().orElseThrow(ReferenceNotFound::new);
    }

    @Override
    public Page<MethodVersion> methodologies(UUID company, String code, Boolean available, int page, int size) {
        requireAccess(company);
        return versions(METHODS, code, available, page, size, this::methodVersion);
    }

    @Override
    public MethodVersion methodology(UUID company, UUID version) {
        requireAccess(company);
        return version(METHODS, version, this::methodVersion);
    }

    private <T> Page<T> versions(String query, String code, Boolean available, int page, int size, RowMapper<T> mapping) {
        var sql = new StringBuilder(query).append(" WHERE true");
        var params = new HashMap<String, Object>();
        equal(sql, params, "codigo", code);
        if (available != null) {
            sql.append(" AND disponible_nuevos = :available");
            params.put("available", available);
        }
        var rows = pageQuery(sql.append(" ORDER BY codigo, version, version_id").toString(), params, page, size)
                .query(mapping).list();
        return page(rows, page, size);
    }

    private <T> T version(String query, UUID id, RowMapper<T> mapping) {
        return jdbc.sql(query + " WHERE version_id = :id").param("id", id)
                .query(mapping).optional().orElseThrow(ReferenceNotFound::new);
    }

    private static void equal(StringBuilder sql, Map<String, Object> params, String column, String value) {
        if (value != null && !value.isBlank()) {
            sql.append(" AND lower(").append(column).append(") = lower(:").append(column).append(")");
            params.put(column, value.strip());
        }
    }

    private JdbcClient.StatementSpec pageQuery(String sql, Map<String, Object> params, int page, int size) {
        return jdbc.sql(sql + " LIMIT :limit OFFSET :offset").params(params)
                .param("limit", size + 1).param("offset", (long) page * size);
    }

    private static <T> Page<T> page(List<T> rows, int page, int size) {
        boolean more = rows.size() > size;
        return new Page<>(more ? rows.subList(0, size) : rows, page, size, more);
    }

    private void requireAccess(UUID company) {
        access.require(company, CompanyPermission.READ_REFERENCE_CATALOGS);
    }

    private static CatalogVersion catalogVersion(ResultSet row, int index) throws SQLException {
        return new CatalogVersion(row.getObject("version_id", UUID.class), row.getString("codigo"),
                row.getString("version"), row.getString("nombre"), row.getString("origen"), row.getString("huella_origen"),
                row.getObject("publicada_en", OffsetDateTime.class).toInstant(), row.getBoolean("disponible_nuevos"));
    }

    private static ElementSummary summary(ResultSet row, int index) throws SQLException {
        return new ElementSummary(row.getObject("id", UUID.class), row.getObject("version_catalogo_id", UUID.class),
                Type.valueOf(row.getString("tipo")), row.getString("codigo"), row.getString("titulo"),
                row.getString("grupo"), row.getString("referencia_origen"));
    }

    private static Element detail(ResultSet row, int index) throws SQLException {
        ElementSummary item = summary(row, index);
        return new Element(item.elementoId(), item.versionId(), item.tipo(), item.codigo(), item.titulo(),
                row.getString("descripcion"), item.grupo(), item.referenciaOrigen());
    }

    private MethodVersion methodVersion(ResultSet row, int index) throws SQLException {
        return new MethodVersion(row.getObject("version_id", UUID.class), row.getString("codigo"),
                row.getString("version"), row.getString("identificador"), row.getString("nombre"),
                row.getString("fundamento"), row.getString("origen"), mapper.readTree(row.getString("reglas")),
                row.getObject("aprobada_en", LocalDate.class), row.getString("aprobada_por"),
                row.getBoolean("disponible_nuevos"));
    }
}
