package dev.triedge.controlplane.validate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.triedge.controlplane.render.ConfigRenderer;
import dev.triedge.controlplane.render.EnvoyResources;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProcessEnvoyValidatorTest {

    private final EnvoyResources resources = new ConfigRenderer("eu", 8080, "edge-sidecar", 9001).render(List.of(), true);

    @TempDir
    Path dir;

    @Test
    void acceptsWhenEnvoyExitsCleanly() throws IOException {
        Path envoy = script("grep -q '\"listeners\"' \"$4\" && exit 0; exit 3");

        assertThat(new ProcessEnvoyValidator(envoy.toString()).validate(resources)).isEmpty();
    }

    @Test
    void reportsEnvoysComplaints() throws IOException {
        Path envoy = script("echo 'starting'; echo; echo 'error initializing configuration: bad cluster'; exit 1");

        assertThat(new ProcessEnvoyValidator(envoy.toString()).validate(resources))
                .containsExactly("starting", "error initializing configuration: bad cluster");
    }

    @Test
    void silentFailureStillFails() throws IOException {
        assertThat(new ProcessEnvoyValidator(script("exit 1").toString()).validate(resources))
                .containsExactly("Envoy rejected the configuration");
    }

    @Test
    void missingBinaryIsAnError() {
        assertThatThrownBy(() -> new ProcessEnvoyValidator(dir.resolve("missing").toString()).validate(resources))
                .isInstanceOf(UncheckedIOException.class);
    }

    private Path script(String body) throws IOException {
        Path script = dir.resolve("envoy-" + System.nanoTime());
        Files.writeString(script, "#!/bin/sh\n" + body + "\n");
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"));
        return script;
    }
}
