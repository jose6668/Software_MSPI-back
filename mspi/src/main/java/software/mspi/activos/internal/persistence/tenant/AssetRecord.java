package software.mspi.activos.internal.persistence.tenant;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import software.mspi.activos.api.AssetInventory.Asset;
import software.mspi.activos.api.AssetInventory.Input;
import software.mspi.activos.api.AssetInventory.References;
import software.mspi.activos.api.AssetInventory.Type;

@Entity
@Table(name = "activos")
class AssetRecord extends CatalogRecord {

    @Column(name = "codigo", updatable = false) String code;
    @Column(name = "codigo_normalizado", updatable = false) String codeKey;
    @Column(name = "descripcion") String description;
    @Column(name = "tipo") @Enumerated(EnumType.STRING) Type type;
    @Column(name = "proceso_id") UUID processId;
    @Column(name = "propietario_id") UUID ownerId;
    @Column(name = "custodio_id") UUID custodianId;
    @Column(name = "ubicacion") String location;
    @Column(name = "valoracion_vigente_id") UUID currentValuation;

    protected AssetRecord() {}

    AssetRecord(UUID company, String code) {
        super(company);
        this.code = code.strip();
        this.codeKey = normalizedCode(code);
    }

    static String normalizedCode(String code) {
        return Normalizer.normalize(code.strip().toUpperCase(Locale.ROOT), Normalizer.Form.NFC);
    }

    References references() {
        return new References(processId, ownerId, custodianId);
    }

    List<String> changeReferences(References input) {
        var changed = new ArrayList<String>();
        noteChange(changed, "procesoId", processId, input.procesoId());
        noteChange(changed, "propietarioId", ownerId, input.propietarioId());
        noteChange(changed, "custodioId", custodianId, input.custodioId());
        processId = input.procesoId();
        ownerId = input.propietarioId();
        custodianId = input.custodioId();
        return changed;
    }

    List<String> update(Input input) {
        var changed = changeReferences(input.referencias());
        noteChange(changed, "nombre", name, input.nombre());
        noteChange(changed, "descripcion", description, input.descripcion());
        noteChange(changed, "tipo", type, input.tipo());
        noteChange(changed, "ubicacion", location, input.ubicacion());
        name = input.nombre();
        description = input.descripcion();
        type = input.tipo();
        location = input.ubicacion();
        return changed;
    }

    private static void noteChange(List<String> fields, String name, Object previous, Object value) {
        if (!Objects.equals(previous, value)) {
            fields.add(name);
        }
    }

    Asset view() {
        return new Asset(id, code, name, description, type, processId, ownerId, custodianId, location,
                state, revision, currentValuation == null ? "PENDIENTE" : "COMPLETO");
    }
}
