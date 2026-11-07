package software.mspi.identidad.internal;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "mspi.recovery.enabled", havingValue = "true")
@EnableConfigurationProperties(RecoverySettings.class)
class RecoveryConfiguration {

    @Bean
    ThreadPoolTaskExecutor recoveryDeliveryExecutor(RecoverySettings settings) {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(settings.queueCapacity());
        executor.setThreadNamePrefix("mspi-recovery-");
        return executor;
    }
}
