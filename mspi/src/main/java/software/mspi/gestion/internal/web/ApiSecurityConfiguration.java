package software.mspi.gestion.internal.web;

import java.time.Clock;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.endpoint.web.WebServerNamespace;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;

import static org.springframework.security.config.Customizer.withDefaults;

@Configuration(proxyBeanMethods = false)
class ApiSecurityConfiguration {

    @Bean
    HttpSessionSecurityContextRepository apiSecurityContexts() {
        var contexts = new HttpSessionSecurityContextRepository();
        contexts.setDisableUrlRewriting(true);
        return contexts;
    }

    @Bean
    HttpSessionCsrfTokenRepository apiCsrfTokens() {
        return new HttpSessionCsrfTokenRepository();
    }

    @Bean
    @Order(1)
    SecurityFilterChain apiSecurity(HttpSecurity http, ApiErrors errors,
                                    HttpSessionSecurityContextRepository contexts,
                                    HttpSessionCsrfTokenRepository csrf, ObjectProvider<Clock> clocks) throws Exception {
        return http
                .securityMatcher("/api/v1", "/api/v1/**")
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/api/v1").permitAll()
                        .requestMatchers(HttpMethod.HEAD, "/api/v1").permitAll()
                        .requestMatchers(HttpMethod.GET, ApiSessionController.CSRF).permitAll()
                        .requestMatchers(HttpMethod.POST, ApiSessionController.SESSION).permitAll()
                        .requestMatchers(HttpMethod.GET, ApiCompanyController.MINE, ApiCompanyController.CONTEXT)
                        .authenticated()
                        .requestMatchers("/api/v1/empresas/**", "/api/v1/mis-empresas").denyAll()
                        .anyRequest().authenticated())
                .requestCache(cache -> cache.disable())
                .securityContext(config -> config.securityContextRepository(contexts))
                .addFilterAfter(new SessionExpiryFilter(clocks.getIfAvailable(Clock::systemUTC)),
                        SecurityContextHolderFilter.class)
                .csrf(config -> config.csrfTokenRepository(csrf))
                .logout(config -> config.disable())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, failure) ->
                                errors.write(request, response, ApiErrors.Problem.forStatus(HttpStatus.UNAUTHORIZED)))
                        .accessDeniedHandler((request, response, failure) -> {
                            var problem = failure instanceof CsrfException
                                    ? new ApiErrors.Problem(HttpStatus.FORBIDDEN, "CSRF_INVALIDO",
                                            "Se requiere un token CSRF válido.")
                                    : ApiErrors.Problem.forStatus(HttpStatus.FORBIDDEN);
                            errors.write(request, response, problem);
                        }))
                .build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain remainingSecurity(HttpSecurity http) throws Exception {
        // Operational health remains available. Authentication is only through the API.
        return http.authorizeHttpRequests(auth -> auth
                        .requestMatchers(EndpointRequest.to(HealthEndpoint.class),
                                EndpointRequest.toAdditionalPaths(WebServerNamespace.SERVER, HealthEndpoint.class))
                        .permitAll()
                        .anyRequest().denyAll())
                .cors(withDefaults())
                .requestCache(cache -> cache.disable())
                .logout(config -> config.disable())
                .build();
    }
}
