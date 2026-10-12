package dev.triedge.common.env;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

public class CommonDefaultsEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    static final int MANAGEMENT_PORT_OFFSET = 1000;

    private static final String DEFAULTS = "triedge-defaults.yaml";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        for (PropertySource<?> source : loadDefaults()) {
            environment.getPropertySources().addLast(source);
        }
        Integer serverPort = environment.getProperty("server.port", Integer.class);
        if (serverPort != null && serverPort > 0 && !environment.containsProperty("management.server.port")) {
            environment.getPropertySources().addLast(new MapPropertySource(
                    "triedge-management-port",
                    Map.of("management.server.port", serverPort + MANAGEMENT_PORT_OFFSET)));
        }
    }

    @Override
    public int getOrder() {
        return ConfigDataEnvironmentPostProcessor.ORDER + 1;
    }

    private static List<PropertySource<?>> loadDefaults() {
        try {
            return new YamlPropertySourceLoader().load(DEFAULTS, new ClassPathResource(DEFAULTS));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}