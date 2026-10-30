package dev.triedge.common.env;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

class CommonDefaultsEnvironmentPostProcessorTest {

    private final CommonDefaultsEnvironmentPostProcessor processor = new CommonDefaultsEnvironmentPostProcessor();

    @Test
    void addsSharedDefaults() {
        StandardEnvironment environment = environmentWith(Map.of());

        processor.postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty("management.endpoints.web.exposure.include"))
                .isEqualTo("health,prometheus");
        assertThat(environment.getProperty("management.tracing.sampling.probability")).isEqualTo("1.0");
    }

    @Test
    void serviceSettingsWinOverDefaults() {
        StandardEnvironment environment = environmentWith(
                Map.of("management.endpoints.web.exposure.include", "health"));

        processor.postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty("management.endpoints.web.exposure.include")).isEqualTo("health");
    }

    @Test
    void derivesManagementPortFromServerPort() {
        StandardEnvironment environment = environmentWith(Map.of("server.port", "8110"));

        processor.postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty("management.server.port", Integer.class)).isEqualTo(9110);
    }

    @Test
    void keepsExplicitManagementPort() {
        StandardEnvironment environment = environmentWith(
                Map.of("server.port", "8110", "management.server.port", "7000"));

        processor.postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty("management.server.port", Integer.class)).isEqualTo(7000);
    }

    @Test
    void leavesManagementPortAloneForRandomServerPort() {
        StandardEnvironment environment = environmentWith(Map.of("server.port", "0"));

        processor.postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.containsProperty("management.server.port")).isFalse();
    }

    @Test
    void runsRightAfterConfigFilesAreLoaded() {
        assertThat(processor.getOrder())
                .isGreaterThan(ConfigDataEnvironmentPostProcessor.ORDER);
    }

    private static StandardEnvironment environmentWith(Map<String, Object> properties) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test", properties));
        return environment;
    }
}
