package software.mspi.empresas.internal.invitations;

import java.net.URI;
import java.time.Duration;

import software.mspi.identidad.api.PasswordPolicy;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("mspi.invitations")
record InvitationSettings(Duration validity, URI acceptanceUrl, String from, int minPasswordLength) {

    InvitationSettings {
        if (validity == null || validity.toSeconds() < 1 || validity.toSeconds() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Configure la vigencia explícita de las invitaciones.");
        }
        if (acceptanceUrl == null || !"https".equalsIgnoreCase(acceptanceUrl.getScheme())
                || acceptanceUrl.getHost() == null || acceptanceUrl.getRawUserInfo() != null
                || acceptanceUrl.getRawFragment() != null || acceptanceUrl.getRawQuery() != null) {
            throw new IllegalArgumentException("Configure una URL HTTPS de aceptación sin consulta, fragmento ni credenciales.");
        }
        if (from == null || !from.matches("[^\\s@<>]+@[^\\s@<>]+")) {
            throw new IllegalArgumentException("Configure el remitente SMTP de las invitaciones.");
        }
        if (!PasswordPolicy.validMinimum(minPasswordLength)) {
            throw new IllegalArgumentException("Configure la longitud mínima para cuentas invitadas.");
        }
    }
}
