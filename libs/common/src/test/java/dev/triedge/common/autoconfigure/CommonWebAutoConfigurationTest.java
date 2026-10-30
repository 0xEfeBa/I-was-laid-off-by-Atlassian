package dev.triedge.common.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import dev.triedge.common.web.ProblemDetailsAdvice;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

class CommonWebAutoConfigurationTest {

    private final WebApplicationContextRunner webRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CommonWebAutoConfiguration.class));

    @Test
    void registersProblemDetailsAdviceInWebApps() {
        webRunner.run(context -> assertThat(context).hasSingleBean(ProblemDetailsAdvice.class));
    }

    @Test
    void backsOffWhenServiceDefinesItsOwnHandler() {
        webRunner.withUserConfiguration(CustomHandlerConfiguration.class).run(context -> {
            assertThat(context).doesNotHaveBean(ProblemDetailsAdvice.class);
            assertThat(context).hasSingleBean(ResponseEntityExceptionHandler.class);
        });
    }

    @Test
    void staysOutOfNonWebApps() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(CommonWebAutoConfiguration.class))
                .run(context -> assertThat(context).doesNotHaveBean(ProblemDetailsAdvice.class));
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomHandlerConfiguration {

        @Bean
        ResponseEntityExceptionHandler customHandler() {
            return new ResponseEntityExceptionHandler() {
            };
        }
    }
}
