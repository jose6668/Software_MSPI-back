package software.mspi.gestion.internal.web;

import java.util.List;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.type.LogicalType;

@Configuration(proxyBeanMethods = false)
class ApiWebConfiguration implements WebMvcConfigurer {

    private final ApiErrors errors;

    ApiWebConfiguration(ApiErrors errors) {
        this.errors = errors;
    }

    @Bean
    FilterRegistrationBean<ApiCorrelationFilter> apiCorrelation() {
        var registration = new FilterRegistrationBean<>(new ApiCorrelationFilter());
        registration.addUrlPatterns("/api/v1", "/api/v1/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }

    @Bean
    static JsonMapperBuilderCustomizer strictTextInputs() {
        return builder -> builder.withCoercionConfig(LogicalType.Textual, coercion -> coercion
                .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail));
    }

    @Override
    public void extendHandlerExceptionResolvers(List<HandlerExceptionResolver> resolvers) {
        resolvers.add(0, errors::resolve);
    }
}
