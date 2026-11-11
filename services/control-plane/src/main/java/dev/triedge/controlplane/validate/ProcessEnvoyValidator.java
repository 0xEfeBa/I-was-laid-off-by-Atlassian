package dev.triedge.controlplane.validate;

import dev.triedge.controlplane.render.EnvoyResources;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;

public class ProcessEnvoyValidator implements EnvoyValidator {

    private static final int MAX_LINES = 20;

    private final String binary;

    public ProcessEnvoyValidator(String binary) {
        this.binary = binary;
    }

    @Override
    public List<String> validate(EnvoyResources resources) {
        Path config = null;
        Path log = null;
        try {
            config = Files.createTempFile("envoy-validate", ".json");
            log = Files.createTempFile("envoy-validate", ".log");
            Files.writeString(config, BootstrapJson.of(resources));
            Process process = new ProcessBuilder(binary, "--mode", "validate", "-c", config.toString(),
                    "--log-level", "critical")
                    .redirectErrorStream(true)
                    .redirectOutput(log.toFile())
                    .start();
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return List.of("Envoy validation timed out");
            }
            String output = Files.readString(log, StandardCharsets.UTF_8);
            if (process.exitValue() == 0) {
                return List.of();
            }
            List<String> lines = output.lines().map(String::strip).filter(line -> !line.isEmpty()).toList();
            return lines.isEmpty() ? List.of("Envoy rejected the configuration")
                    : lines.subList(Math.max(0, lines.size() - MAX_LINES), lines.size());
        } catch (IOException e) {
            throw new UncheckedIOException("Could not run " + binary, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of("Envoy validation was interrupted");
        } finally {
            delete(config);
            delete(log);
        }
    }

    private static void delete(@Nullable Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            file.toFile().deleteOnExit();
        }
    }
}
