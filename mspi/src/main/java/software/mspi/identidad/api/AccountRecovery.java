package software.mspi.identidad.api;

import java.util.UUID;

public interface AccountRecovery {

    void request(String email, String address, UUID correlation);

    void confirm(String token, String password, String address);
}
