package software.mspi.empresas.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import software.mspi.auditoria.api.ActivityLog.Activity;
import software.mspi.auditoria.api.ActivityLog.Filters;
import software.mspi.auditoria.api.ActivityLog.Window;

public interface CompanyAdministration {

    List<Activity> activity(UUID companyId, Filters filters, Window window, int limit);

    Configuration configuration(UUID companyId);

    void configure(UUID companyId, Configuration input, UUID correlation);

    Page<Member> members(UUID companyId, int page, int size);

    Page<Invitation> invitations(UUID companyId, int page, int size);

    Page<Responsible> responsibles(UUID companyId, int page, int size);

    void changeRoles(UUID companyId, UUID memberId, long revision, List<CompanyRole> roles, String reason,
                     UUID correlation);

    void revoke(UUID companyId, UUID memberId, long revision, String reason, UUID correlation);

    record Configuration(String nombre, String descripcion, String zonaHoraria, long revision) {}

    record Page<T>(List<T> elementos, int pagina, int tamano, boolean hayMas) {}

    record Member(UUID membresiaId, String nombreVisible, String correo, List<CompanyRole> roles,
                  String estado, Instant revocadaEn, long revision) {}

    record Invitation(UUID invitacionId, String correo, List<CompanyRole> roles, String estado,
                      Instant creadaEn, Instant venceEn, Instant aceptadaEn, Instant revocadaEn) {}

    record Responsible(UUID membresiaId, String nombreVisible) {}
}
