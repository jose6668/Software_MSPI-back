package software.mspi.activos.api;

import java.util.List;
import java.util.UUID;

public interface ProcessCatalog {

    Page<Process> processes(Scope scope, State state, UUID responsibleId, int page, int size);
    Process process(Scope scope, UUID id);
    Process createProcess(Scope scope, ProcessInput input);
    Process updateProcess(Scope scope, UUID id, long revision, ProcessInput input);
    Process archiveProcess(Scope scope, UUID id, long revision, String reason);

    Page<Responsible> responsibles(Scope scope, State state, int page, int size);
    Responsible responsible(Scope scope, UUID id);
    Responsible createResponsible(Scope scope, ResponsibleInput input);
    Responsible updateResponsible(Scope scope, UUID id, long revision, ResponsibleInput input);
    Responsible archiveResponsible(Scope scope, UUID id, long revision, String reason);

    enum State { ACTIVO, ARCHIVADO }
    enum ResponsibleType { PERSONA, CARGO, AREA }

    record Scope(UUID actor, UUID company, UUID correlation) {}

    record Page<T>(List<T> elementos, int pagina, int tamano, boolean hayMas) {}

    record Process(UUID procesoId, String nombre, String descripcion, UUID responsableId, State estado, long revision) {}

    record Responsible(UUID responsableId, String nombre, ResponsibleType tipo, UUID membresiaId,
                       State estado, long revision) {}

    record ProcessInput(String nombre, String descripcion, UUID responsableId) {}

    record ResponsibleInput(String nombre, ResponsibleType tipo, UUID membresiaId) {}
}
