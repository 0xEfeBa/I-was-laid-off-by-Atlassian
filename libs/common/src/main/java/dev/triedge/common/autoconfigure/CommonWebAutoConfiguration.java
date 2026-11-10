package dev.triedge.common.autoconfigure;

import dev.triedge.common.tracing.RequestsOnlySampler;
import dev.triedge.common.web.ProblemDetailsAdvice;
import io.micrometer.tracing.Tracer;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.OpenTelemetryTracingAutoConfiguration;
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.SdkTracerProviderBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@AutoConfiguration(after = OpenTelemetryTracingAutoConfiguration.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class CommonWebAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(ResponseEntityExceptionHandler.class)
    ProblemDetailsAdvice problemDetailsAdvice(ObjectProvider<Tracer> tracer) {
        return new ProblemDetailsAdvice(tracer.getIfAvailable(() -> Tracer.NOOP));
    }

    @Bean
    @ConditionalOnBean(Sampler.class)
    SdkTracerProviderBuilderCustomizer requestsOnlySampler(Sampler sampler) {
        return builder -> builder.setSampler(new RequestsOnlySampler(sampler));
    }
}