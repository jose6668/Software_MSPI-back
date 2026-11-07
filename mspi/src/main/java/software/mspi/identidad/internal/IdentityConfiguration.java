package software.mspi.identidad.internal;

import java.time.Clock;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(IdentitySettings.class)
class IdentityConfiguration {

    @Bean
    PasswordEncoder localPasswordEncoder() {
        return LocalPasswordHash.encoder();
    }

    @Bean
    AuthenticationManager localAuthenticationManager(JdbcClient jdbc, PasswordEncoder encoder,
                                                      IdentitySettings settings, ObjectProvider<Clock> clocks) {
        return new ProviderManager(new LocalAccountProvider(jdbc, encoder, settings,
                clocks.getIfAvailable(Clock::systemUTC)));
    }
}
