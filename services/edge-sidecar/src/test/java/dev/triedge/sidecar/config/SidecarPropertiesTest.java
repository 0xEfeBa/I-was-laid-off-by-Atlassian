package dev.triedge.sidecar.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class SidecarPropertiesTest {

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(Properties.class)
            .withPropertyValues("triedge.sidecar.issuer=tri-edge",
                    "triedge.sidecar.jwks-url=http://identity:8130/.well-known/jwks.json");

    @Test
    void startsWithValidSettings() {
        context.run(started -> assertThat(started).hasNotFailed()
                .getBean(SidecarProperties.class).extracting(SidecarProperties::grpcThreads).isEqualTo(128));
    }

    @Test
    void refusesToStartWithoutAnIssuer() {
        context.withPropertyValues("triedge.sidecar.issuer=").run(started -> assertThat(started).hasFailed()
                .getFailure().rootCause().hasMessageContaining("triedge.sidecar.issuer must be set"));
    }

    @Test
    void refusesToStartWithABrokenJwksUrl() {
        for (String url : new String[] {"ftp://identity/jwks", "not a url", "htps://identity/jwks", "/jwks.json"}) {
            context.withPropertyValues("triedge.sidecar.jwks-url=" + url).run(started -> assertThat(started).hasFailed()
                    .getFailure().rootCause().hasMessageContaining("triedge.sidecar.jwks-url"));
        }
    }

    @Test
    void refusesToStartWithoutGrpcThreads() {
        context.withPropertyValues("triedge.sidecar.grpc-threads=0").run(started -> assertThat(started).hasFailed()
                .getFailure().rootCause().hasMessageContaining("grpc-threads"));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(SidecarProperties.class)
    static class Properties {
    }
}
