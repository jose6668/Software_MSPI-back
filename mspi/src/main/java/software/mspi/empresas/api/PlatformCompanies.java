package software.mspi.empresas.api;

import java.util.List;
import java.util.UUID;

/** Platform lifecycle operations. No enterprise content is returned by this contract. */
public interface PlatformCompanies {
    Page list(int page, int size);
    Company get(UUID id);
    Company reserve(Reservation input, UUID key, UUID correlation);
    Company complete(UUID id, long revision, boolean reissue, UUID correlation);
    Company changeAvailability(UUID id, long revision, boolean reactivate, String reason, UUID correlation);

    record Reservation(String nombre, String descripcion, String zonaHoraria, String contactoInicial) {}

    record Company(UUID empresaId, String nombre, String descripcion, String zonaHoraria, String estado,
                   String versionEstructura, long revision, String pasoPreparacion, String resultadoPreparacion,
                   UUID referenciaFallo, String motivoEstado, UUID invitacionInicialId) {}

    record Page(List<Company> empresas, int pagina, int tamano, boolean hayMas) {
        public Page { empresas = List.copyOf(empresas); }
    }
}
