package software.mspi.gestion.internal.web;

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
import org.springframework.security.web.csrf.CsrfException;

import static org.springframework.security.config.Customizer.withDefaults;

@Configuration(proxyBeanMethods = false)
class ApiSecurityConfiguration {

    @Bean
    @Order(1)
    SecurityFilterChain apiSecurity(HttpSecurity http, ApiErrors errors) throws Exception {
        return http
                .securityMatcher("/api/v1", "/api/v1/**")
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/api/v1").permitAll()
                        .requestMatchers(HttpMethod.HEAD, "/api/v1").permitAll()
                        .requestMatchers("/api/v1/empresas/**", "/api/v1/mis-empresas").denyAll()
                        .anyRequest().authenticated())
                .requestCache(cache -> cache.disable())
                .csrf(withDefaults())
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
        // Preserve Spring Boot's existing rules outside the API while session handling is developed in HU-007.
        return http.authorizeHttpRequests(auth -> auth
                        .requestMatchers(EndpointRequest.to(HealthEndpoint.class),
                                EndpointRequest.toAdditionalPaths(WebServerNamespace.SERVER, HealthEndpoint.class))
                        .permitAll()
                        .anyRequest().authenticated())
                .cors(withDefaults())
                .formLogin(withDefaults())
                .httpBasic(withDefaults())
                .build();
    }
}
