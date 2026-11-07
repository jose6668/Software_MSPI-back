package software.mspi.activos.internal.persistence.tenant;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.springframework.dao.OptimisticLockingFailureException;
import software.mspi.activos.api.AssetClassification.Authorization;
import software.mspi.activos.api.AssetClassification.Input;
import software.mspi.activos.api.AssetClassification.Level;
import software.mspi.activos.api.AssetClassification.PersonalDataType;
import software.mspi.activos.api.AssetClassification.Status;
import software.mspi.activos.api.AssetClassification.Valuation;
import software.mspi.activos.api.ClassificationFailure;

@Entity
@Table(name = "valoraciones_activo")
class AssetValuation {

    @Id UUID id;
    @Column(name = "empresa_id", updatable = false) UUID companyId;
    @Column(name = "activo_id", updatable = false) UUID assetId;
    @Column(name = "numero", updatable = false) int number;
    @Version @Column(name = "revision") long revision;
    @Column(name = "metodologia", updatable = false) String methodology;
    @Column(name = "version_tipos", updatable = false) String catalogueVersion;

    @Column(name = "confidencialidad") Integer confidentiality;
    @Column(name = "integridad") Integer integrity;
    @Column(name = "disponibilidad") Integer availability;
    @Column(name = "fundamento") String rationale;
    @Column(name = "datos_personales") Boolean personal;
    @Column(name = "datos_menores") Boolean minors;
    @ElementCollection
    @CollectionTable(name = "valoracion_tipos_dato", joinColumns = @JoinColumn(name = "valoracion_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "tipo_codigo")
    Set<PersonalDataType> types = new LinkedHashSet<>();
    @Column(name = "finalidad") String purpose;
    @Column(name = "autorizacion") @Enumerated(EnumType.STRING) Authorization authorization;
    @Column(name = "justificacion") String justification;
    @Column(name = "motivo_revision", updatable = false) String reason;

    @Column(name = "criticidad") Integer criticality;
    @Column(name = "creada_por", updatable = false) UUID createdBy;
    @Column(name = "creada_en", updatable = false) Instant createdAt;
    @Column(name = "actualizada_por") UUID updatedBy;
    @Column(name = "actualizada_en") Instant updatedAt;
    @Column(name = "confirmada_por") UUID confirmedBy;
    @Column(name = "confirmada_en") Instant confirmedAt;

    protected AssetValuation() {}

    AssetValuation(UUID company, UUID asset, int number, UUID actor, Instant now, String reason) {
        id = UUID.randomUUID();
        companyId = company;
        assetId = asset;
        this.number = number;
        methodology = "CID_MAX_V1";
        catalogueVersion = "DATOS_PERSONALES_V1";
        createdBy = actor;
        createdAt = now;
        this.reason = reason;
    }

    void requireDraft(long expected) {
        if (confirmedAt != null) {
            throw new ClassificationFailure(ClassificationFailure.Reason.CONFIRMED, List.of());
        }
        if (revision != expected) {
            throw new OptimisticLockingFailureException("Revisión desactualizada.");
        }
    }

    void update(Input input, UUID actor, Instant now) {
        confidentiality = input.confidencialidad();
        integrity = input.integridad();
        availability = input.disponibilidad();
        rationale = input.fundamento();
        personal = input.contieneDatosPersonales();
        minors = input.contieneDatosMenores();
        types.clear();
        types.addAll(input.tiposDatosPersonales());
        purpose = input.finalidadTratamiento();
        authorization = input.autorizacionTratamiento();
        justification = input.justificacionNoAplica();
        updatedBy = actor;
        updatedAt = now;
    }

    void confirm(UUID actor, Instant now) {
        criticality = Math.max(confidentiality, Math.max(integrity, availability));
        confirmedBy = actor;
        confirmedAt = now;
        updatedBy = actor;
        updatedAt = now;
    }

    Input input() {
        return new Input(confidentiality, integrity, availability, rationale, personal, minors,
                types.stream().sorted().toList(), purpose, authorization, justification);
    }

    Valuation view(UUID current) {
        return new Valuation(id, assetId, number, revision, methodology, catalogueVersion,
                confirmedAt == null ? Status.PENDIENTE : Status.COMPLETO, id.equals(current), criticality,
                level(), input(), reason, createdBy, createdAt, updatedBy, updatedAt, confirmedBy, confirmedAt);
    }

    private Level level() {
        if (criticality == null) {
            return null;
        }
        return switch (criticality) {
            case 1 -> Level.BAJA;
            case 3 -> Level.MEDIA;
            case 5 -> Level.ALTA;
            default -> throw new IllegalStateException("Criticidad persistida fuera de escala.");
        };
    }
}
