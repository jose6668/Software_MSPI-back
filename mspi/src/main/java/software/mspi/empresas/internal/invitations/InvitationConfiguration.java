package software.mspi.empresas.internal.invitations;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "mspi.invitations.enabled", havingValue = "true")
@EnableConfigurationProperties(InvitationSettings.class)
class InvitationConfiguration {}
