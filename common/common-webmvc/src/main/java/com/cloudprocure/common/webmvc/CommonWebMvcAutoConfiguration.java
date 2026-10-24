package com.cloudprocure.common.webmvc;

import io.micrometer.tracing.Tracer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import tools.jackson.databind.ObjectMapper;

/** 显式自动装配，不扩大各应用的包扫描范围。 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@Import(GlobalExceptionHandler.class)
public class CommonWebMvcAutoConfiguration {

    @Bean
    FilterRegistrationBean<RequestTraceFilter> requestTraceFilter(ObjectProvider<Tracer> tracer) {
        var registration = new FilterRegistrationBean<>(new RequestTraceFilter(tracer.getIfAvailable()));
        // 排在HTTP观测之后、安全过滤链之前，以便优先取已有span。
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        registration.setAsyncSupported(true);
        return registration;
    }

    @Bean
    @ConditionalOnMissingBean
    ProblemSecurityHandlers problemSecurityHandlers(ObjectMapper mapper) {
        return new ProblemSecurityHandlers(mapper);
    }
}
