package software.mspi.activos.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import software.mspi.activos.api.ProcessCatalog.Page;
import software.mspi.activos.api.ProcessCatalog.Scope;

public interface AssetClassification {

    Page<Valuation> list(Scope scope, UUID assetId, int page, int size);
    Valuation get(Scope scope, UUID assetId, UUID valuationId);
    Valuation start(Scope scope, UUID assetId, Input input, String reason);
    Valuation edit(Scope scope, UUID assetId, UUID valuationId, long revision, Input input);
    Valuation confirm(Scope scope, UUID assetId, UUID valuationId, long revision);

    enum PersonalDataType { IDENTIFICACION, CONTACTO, FINANCIEROS, SALUD, BIOMETRICOS, OTROS }
    enum Authorization { SI, NO, NO_APLICA }
    enum Status { PENDIENTE, COMPLETO }
    enum Level { BAJA, MEDIA, ALTA }

    record Input(Integer confidencialidad, Integer integridad, Integer disponibilidad, String fundamento,
                 Boolean contieneDatosPersonales, Boolean contieneDatosMenores, List<PersonalDataType> tiposDatosPersonales,
                 String finalidadTratamiento, Authorization autorizacionTratamiento, String justificacionNoAplica) {}

    record Valuation(UUID valoracionId, UUID activoId, int numero, long revision, String metodologia,
                     String versionTiposDatos, Status estado, boolean vigente, Integer criticidad, Level nivel,
                     Input entradas, String motivoRevision, UUID creadaPor, Instant creadaEn, UUID actualizadaPor,
                     Instant actualizadaEn, UUID confirmadaPor, Instant confirmadaEn) {}
}
