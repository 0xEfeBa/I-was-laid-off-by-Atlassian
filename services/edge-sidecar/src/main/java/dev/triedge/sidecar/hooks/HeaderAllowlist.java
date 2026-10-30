package dev.triedge.sidecar.hooks;

import dev.triedge.sidecar.authz.RouteRule;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public class HeaderAllowlist {

    public static final String TENANT_PREFIX = "x-tenant-";
    public static final String TARGET_REGION = "x-target-region";
    public static final String BACKEND_SET = "x-backend-set";

    public Map<String, String> filter(RouteRule rule, Map<String, String> hookHeaders) {
        Map<String, String> allowed = new LinkedHashMap<>();
        hookHeaders.forEach((rawName, value) -> {
            String name = rawName.toLowerCase(Locale.ROOT);
            if (name.startsWith(TENANT_PREFIX)
                    || (name.equals(TARGET_REGION) && rule.regions().contains(value))
                    || (name.equals(BACKEND_SET) && rule.backends().contains(value))) {
                allowed.put(name, value);
            }
        });
        return allowed;
    }
}
