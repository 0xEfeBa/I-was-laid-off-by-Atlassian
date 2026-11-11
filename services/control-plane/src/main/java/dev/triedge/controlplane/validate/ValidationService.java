package dev.triedge.controlplane.validate;

import dev.triedge.controlplane.render.ConfigRenderer;
import dev.triedge.controlplane.source.ActiveTenant;
import dev.triedge.controlplane.source.TenantDirectory;
import dev.triedge.tenant.TenantSpec;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ValidationService {

    private static final Logger log = LoggerFactory.getLogger(ValidationService.class);

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
        ActiveTenant proposed = new ActiveTenant(UUID.randomUUID(), candidate.name(), Integer.MAX_VALUE, candidate, false);
        List<ActiveTenant> others = directory.all().stream().filter(t -> !t.name().equals(candidate.name())).toList();
        List<String> together = envoy.validate(renderer.render(with(others, proposed), true));
        if (together.isEmpty()) {
            return new Outcome(true, List.of());
        }
        List<ActiveTenant> platform = others.stream().filter(ActiveTenant::system).toList();
        List<String> alone = envoy.validate(renderer.render(with(platform, proposed), true));
        if (!alone.isEmpty()) {
            return new Outcome(false, alone);
        }
        List<String> current = envoy.validate(renderer.render(others, true));
        if (!current.isEmpty()) {
            log.error("The current configuration no longer passes Envoy validation: {}", current);
            throw new ValidationUnavailable("The platform configuration cannot be validated right now; try again later");
        }
        log.warn("Tenant {} conflicts with other tenants: {}", candidate.name(), together);
        return new Outcome(false, List.of("The configuration conflicts with another tenant's configuration"));
    }

    private static List<ActiveTenant> with(List<ActiveTenant> tenants, ActiveTenant added) {
        List<ActiveTenant> all = new ArrayList<>(tenants);
        all.add(added);
        return all;
    }

    public static class ValidationUnavailable extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public ValidationUnavailable(String message) {
            super(message);
        }
    }
}
