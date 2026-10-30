package dev.triedge.controlplane.source;

import dev.triedge.tenant.TenantSpecParser;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

public final class SystemTenants {

    private SystemTenants() {
    }

    public static List<ActiveTenant> load(@Nullable String directory) {
        if (directory == null || directory.isBlank()) {
            return List.of();
        }
        List<ActiveTenant> tenants = new ArrayList<>();
        try (Stream<Path> files = Files.list(Path.of(directory))) {
            for (Path file : files.filter(f -> f.toString().endsWith(".yaml")).sorted().toList()) {
                TenantSpecParser.Result result = TenantSpecParser.parse(Files.readString(file));
                if (result instanceof TenantSpecParser.Result.Invalid invalid) {
                    throw new IllegalStateException("System tenant " + file.getFileName() + " is invalid: " + invalid.errors());
                }
                TenantSpecParser.Result.Valid valid = (TenantSpecParser.Result.Valid) result;
                UUID id = UUID.nameUUIDFromBytes(("system:" + valid.spec().name()).getBytes(StandardCharsets.UTF_8));
                tenants.add(new ActiveTenant(id, valid.spec().name(), 0, valid.spec(), true));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read system tenants from " + directory, e);
        }
        return List.copyOf(tenants);
    }
}
