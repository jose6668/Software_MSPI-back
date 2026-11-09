package software.mspi.auditoria.api;

import java.util.UUID;

/** Operational security events accept only a fixed category and a server correlation ID. */
public interface SecurityIncidents {
    enum Type {
        LOGIN_FALLIDO, SIN_SESION, ACCESO_DENEGADO, CSRF_INVALIDO, INTENTOS_LIMITADOS,
        CUOTA_EXCEDIDA, CAPACIDAD_AGOTADA, CARGA_RECHAZADA, AMENAZA_EVIDENCIA, INSPECCION_NO_DISPONIBLE
    }

    void record(Type type, UUID correlation);
}
