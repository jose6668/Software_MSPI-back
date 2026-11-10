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
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.header.HeaderWriterFilter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import software.mspi.identidad.api.ActiveSession;

import static org.springframework.security.config.Customizer.withDefaults;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ApiLoadSettings.class)
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
                                    HttpSessionCsrfTokenRepository csrf, ActiveSession sessions,
                                    ApiLoadGuard load, ApiLoadSettings loadSettings) throws Exception {
        return http
                .securityMatcher("/api/v1", "/api/v1/**")
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/api/v1").permitAll()
                        .requestMatchers(HttpMethod.HEAD, "/api/v1").permitAll()
                        .requestMatchers(HttpMethod.GET, ApiSessionController.CSRF).permitAll()
                        .requestMatchers(HttpMethod.POST, ApiSessionController.SESSION).permitAll()
                        .requestMatchers(HttpMethod.POST, ApiRecoveryController.REQUEST, ApiRecoveryController.CONFIRM)
                        .permitAll()
                        .requestMatchers(HttpMethod.POST, ApiInvitationController.ENROLL).permitAll()
                        .requestMatchers(HttpMethod.POST, ApiInvitationController.ISSUE, ApiInvitationController.REVOKE,
                                ApiInvitationController.ACCEPT).authenticated()
                        .requestMatchers(HttpMethod.GET, ApiCompanyController.MINE, ApiCompanyController.CONTEXT)
                        .authenticated()
                        .requestMatchers(HttpMethod.GET, ApiCompanyAdministrationController.CONFIGURATION,
                                ApiCompanyAdministrationController.MEMBERS, ApiCompanyAdministrationController.RESPONSIBLES,
                                ApiInvitationController.ISSUE).authenticated()
                        .requestMatchers(HttpMethod.PUT, ApiCompanyAdministrationController.CONFIGURATION,
                                ApiCompanyAdministrationController.ROLES).authenticated()
                        .requestMatchers(HttpMethod.POST, ApiCompanyAdministrationController.REVOKE).authenticated()
                        .requestMatchers(HttpMethod.GET, ApiProcessCatalogController.PROCESSES,
                                ApiProcessCatalogController.PROCESS, ApiProcessCatalogController.RESPONSIBLES,
                                ApiProcessCatalogController.RESPONSIBLE).authenticated()
                        .requestMatchers(HttpMethod.POST, ApiProcessCatalogController.PROCESSES,
                                ApiProcessCatalogController.RESPONSIBLES, ApiProcessCatalogController.ARCHIVE_PROCESS,
                                ApiProcessCatalogController.ARCHIVE_RESPONSIBLE).authenticated()
                        .requestMatchers(HttpMethod.PUT, ApiProcessCatalogController.PROCESS,
                                ApiProcessCatalogController.RESPONSIBLE).authenticated()
                        .requestMatchers(HttpMethod.GET, ApiAssetInventoryController.ASSETS,
                                ApiAssetInventoryController.ASSET, ApiAssetInventoryController.HISTORY).authenticated()
                        .requestMatchers(HttpMethod.POST, ApiAssetInventoryController.ASSETS,
                                ApiAssetInventoryController.ARCHIVE, ApiAssetInventoryController.RESTORE).authenticated()
                        .requestMatchers(HttpMethod.PUT, ApiAssetInventoryController.ASSET).authenticated()
                        .requestMatchers(HttpMethod.GET, ApiAssetClassificationController.CLASSIFICATIONS,
                                ApiAssetClassificationController.CLASSIFICATION).authenticated()
                        .requestMatchers(HttpMethod.POST, ApiAssetClassificationController.CLASSIFICATIONS,
                                ApiAssetClassificationController.CONFIRM).authenticated()
                        .requestMatchers(HttpMethod.PUT, ApiAssetClassificationController.CLASSIFICATION).authenticated()
                        .requestMatchers(HttpMethod.GET, ApiReferenceCatalogController.CATALOGS,
                                ApiReferenceCatalogController.CATALOG, ApiReferenceCatalogController.ELEMENTS,
                                ApiReferenceCatalogController.ELEMENT, ApiReferenceCatalogController.METHODS,
                                ApiReferenceCatalogController.METHOD).authenticated()
                        .requestMatchers(HttpMethod.GET, ApiEvaluationController.EVALUATIONS,
                                ApiEvaluationController.EVALUATION, ApiEvaluationController.RESPONSES,
                                ApiEvaluationController.RESULTS, ApiEvaluationController.FINDINGS).authenticated()
                        .requestMatchers(HttpMethod.POST, ApiEvaluationController.EVALUATIONS,
                                ApiEvaluationController.FINDINGS).authenticated()
                        .requestMatchers(HttpMethod.PUT, ApiEvaluationController.RESPONSE,
                                ApiEvaluationController.FINDING).authenticated()
                        .requestMatchers(HttpMethod.GET, ApiEvaluationLifecycleController.CLOSURE).authenticated()
                        .requestMatchers(HttpMethod.POST, ApiEvaluationLifecycleController.SUBMIT,
                                ApiEvaluationLifecycleController.RETURN, ApiEvaluationLifecycleController.CLOSE,
                                ApiEvaluationLifecycleController.CORRECTIONS, ApiEvaluationLifecycleController.CONFIRM)
                        .authenticated()
                        .requestMatchers(HttpMethod.GET, ApiEvidenceController.EVIDENCE,
                                ApiEvidenceController.CONTENT, ApiEvidenceController.ACTION_CONTEXT_CONTENT).authenticated()
                        .requestMatchers(HttpMethod.POST, ApiEvidenceController.EVIDENCE).authenticated()
                        .requestMatchers(HttpMethod.GET, ApiControlImplementationController.CONTROLS,
                                ApiControlImplementationController.CONTROL, ApiControlImplementationController.VERSIONS,
                                ApiControlImplementationController.VERIFICATIONS, ApiControlEvidenceController.EVIDENCE,
                                ApiControlEvidenceController.CONTENT).authenticated()
                        .requestMatchers(HttpMethod.POST, ApiControlImplementationController.CONTROLS,
                                ApiControlImplementationController.START, ApiControlImplementationController.APPLY,
                                ApiControlImplementationController.CHANGE, ApiControlImplementationController.VERIFY,
                                ApiControlEvidenceController.EVIDENCE).authenticated()
                        .requestMatchers(HttpMethod.GET, ApiInherentRiskController.RISKS, ApiInherentRiskController.RISK,
                                ApiInherentRiskController.ASSESSMENTS, ApiInherentRiskController.LINKS,
                                ApiInherentRiskController.ACCEPTANCES).authenticated()
                        .requestMatchers(HttpMethod.POST, ApiInherentRiskController.RISKS, ApiInherentRiskController.ANALYZE,
                                ApiInherentRiskController.ASSESSMENTS, ApiInherentRiskController.CONFIRM,
                                ApiInherentRiskController.LINKS, ApiInherentRiskController.ACCEPTANCES,
                                ApiInherentRiskController.TRANSITIONS, ApiInherentRiskController.RELATED).authenticated()
                        .requestMatchers(HttpMethod.PUT, ApiInherentRiskController.RISK, ApiInherentRiskController.ASSESSMENT)
                        .authenticated()
                        .requestMatchers(HttpMethod.GET, ApiResidualRiskController.RESIDUALS).authenticated()
                        .requestMatchers(HttpMethod.POST, ApiResidualRiskController.RESIDUALS, ApiResidualRiskController.CONFIRM)
                        .authenticated()
                        .requestMatchers(HttpMethod.PUT, ApiResidualRiskController.RESIDUAL).authenticated()
                        .requestMatchers(HttpMethod.GET, ApiImprovementActionController.ACTIONS,
                                ApiImprovementActionController.ACTION, ApiImprovementActionController.HISTORY,
                                ApiEvidenceController.ACTION_EVIDENCE, ApiEvidenceController.ACTION_FILE).authenticated()
                        .requestMatchers(HttpMethod.POST, ApiImprovementActionController.ACTIONS,
                                ApiImprovementActionController.CANCEL, ApiImprovementActionController.ADVANCES,
                                ApiImprovementActionController.DELIVERIES, ApiImprovementActionController.DECISION,
                                ApiEvidenceController.ACTION_EVIDENCE).authenticated()
                        .requestMatchers(HttpMethod.PUT, ApiImprovementActionController.ACTION).authenticated()
                        .requestMatchers(HttpMethod.GET, ApiCompanyPanelController.PANEL).authenticated()
                        .requestMatchers("/api/v1/empresas/**", "/api/v1/mis-empresas").denyAll()
                        .anyRequest().authenticated())
                .requestCache(cache -> cache.disable())
                .securityContext(config -> config.securityContextRepository(contexts))
                .addFilterAfter(new ApiLoadFilter(load, loadSettings, errors, true), HeaderWriterFilter.class)
                .addFilterAfter(new SessionExpiryFilter(sessions, errors), ApiLoadFilter.class)
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
    SecurityFilterChain remainingSecurity(HttpSecurity http, ApiErrors errors,
                                          ApiLoadGuard load, ApiLoadSettings loadSettings) throws Exception {
        // Operational health remains available. Authentication is only through the API.
        return http.authorizeHttpRequests(auth -> auth
                        .requestMatchers(EndpointRequest.to(HealthEndpoint.class),
                                EndpointRequest.toAdditionalPaths(WebServerNamespace.SERVER, HealthEndpoint.class))
                        .permitAll()
                        .anyRequest().denyAll())
                .cors(withDefaults())
                .addFilterAfter(new ApiLoadFilter(load, loadSettings, errors, false), HeaderWriterFilter.class)
                .requestCache(cache -> cache.disable())
                .logout(config -> config.disable())
                .build();
    }
}
