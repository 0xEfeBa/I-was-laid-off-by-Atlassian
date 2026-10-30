package dev.triedge.controlplane.validate;

import dev.triedge.tenant.TenantSpec;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

public class NamespaceOwnershipCheck {

    private static final Pattern IP_LITERAL = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");

    private final Set<String> forbiddenHosts;

    public NamespaceOwnershipCheck(List<String> forbiddenHosts) {
        this.forbiddenHosts = Set.copyOf(forbiddenHosts);
    }

    public List<String> violations(TenantSpec spec) {
        List<String> violations = new ArrayList<>();
        for (TenantSpec.Backend backend : spec.backends()) {
            if (!allowed(backend.host())) {
                violations.add("/backends: '" + backend.name() + "' points at " + backend.host()
                        + ", which tenants may not use");
            }
        }
        if (spec.hook() != null && !allowed(spec.hook().host())) {
            violations.add("/hook: " + spec.hook().host() + " is not an address tenants may use");
        }
        return violations;
    }

    private boolean allowed(String host) {
        return !forbiddenHosts.contains(host)
                && !host.endsWith(".localhost")
                && !host.endsWith(".internal")
                && !IP_LITERAL.matcher(host).matches();
    }
}
