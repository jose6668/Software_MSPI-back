package software.mspi.activos.api;

import java.util.UUID;

import software.mspi.activos.api.ProcessCatalog.Page;
import software.mspi.activos.api.ProcessCatalog.Scope;
import software.mspi.activos.api.ProcessCatalog.State;
import software.mspi.auditoria.api.ActivityLog.Entry;

public interface AssetInventory {

    Page<Asset> list(Scope scope, Filter filter, int page, int size);
    Asset get(Scope scope, UUID id);
    Asset create(Scope scope, String code, Input input);
    Asset edit(Scope scope, UUID id, long revision, Input input);
    Asset archive(Scope scope, UUID id, long revision, String reason);
    Asset restore(Scope scope, UUID id, long revision, String reason, References replacements);
    Page<Entry> history(Scope scope, UUID id, int page, int size);

    enum Type { INFORMACION, HARDWARE, SOFTWARE, SERVICIO, RECURSO_HUMANO, INSTALACION, RED }

    record References(UUID procesoId, UUID propietarioId, UUID custodioId) {}

    record Input(String nombre, String descripcion, Type tipo, References referencias, String ubicacion) {}

    record Filter(String texto, State estado, Type tipo, UUID procesoId) {}

    record Asset(UUID activoId, String codigo, String nombre, String descripcion, Type tipo,
                 UUID procesoId, UUID propietarioId, UUID custodioId, String ubicacion,
                 State estado, long revision, String estadoClasificacion) {}
}
