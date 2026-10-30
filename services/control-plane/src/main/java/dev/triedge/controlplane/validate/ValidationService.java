package dev.triedge.controlplane.validate;

import dev.triedge.controlplane.render.ConfigRenderer;
import dev.triedge.controlplane.source.ActiveTenant;
import dev.triedge.controlplane.source.TenantDirectory;
import dev.triedge.tenant.TenantSpec;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class ValidationService {

    public record Outcome(boolean valid, List<String> errors) {
    }

    private final TenantDirectory directory;
    private final NamespaceOwnershipCheck ownership;
    private final ConfigRenderer renderer;
    private final EnvoyValidator envoy;

    public ValidationService(TenantDirectory directory, NamespaceOwnershipCheck ownership, ConfigRenderer renderer,
            EnvoyValidator envoy) {
        this.directory = directory;
        this.ownership = ownership;
        this.renderer = renderer;
        this.envoy = envoy;
    }

    public Outcome validate(TenantSpec candidate) {
        List<String> errors = new ArrayList<>();
        directory.get(candidate.name()).filter(ActiveTenant::system)
                .ifPresent(system -> errors.add("/name: '" + candidate.name() + "' is reserved by the platform"));
        for (String host : candidate.hosts()) {
            directory.hostOwner(host)
                    .filter(owner -> !owner.equals(candidate.name()))
                    .ifPresent(owner -> errors.add("/hosts: " + host + " is already used by another tenant"));
        }
        errors.addAll(ownership.violations(candidate));
        if (!errors.isEmpty()) {
            return new Outcome(false, List.copyOf(errors));
        }
        List<ActiveTenant> tenants = new ArrayList<>(directory.all().stream()
                .filter(t -> !t.name().equals(candidate.name()))
                .toList());
        tenants.add(new ActiveTenant(UUID.randomUUID(), candidate.name(), Integer.MAX_VALUE, candidate, false));
        List<String> envoyErrors = envoy.validate(renderer.render(tenants, true));
        return new Outcome(envoyErrors.isEmpty(), envoyErrors);
    }
}
