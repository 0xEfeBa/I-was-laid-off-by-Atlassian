package dev.triedge.sidecar.authz;

import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

public record RouteRule(
        String tenant,
        Auth auth,
        List<String> scopes,
        @Nullable String hook,
        boolean hookFailOpen,
        List<String> regions,
        List<String> backends,
        RouteBy routeBy) {

    public static final String METADATA_NAMESPACE = "triedge.auth";

    public enum Auth {
        NONE,
        JWT,
        PAT,
        JWT_OR_PAT,
        DENY
    }

    public enum RouteBy {
        LOCAL,
        HOME
    }

    public boolean acceptsAccessTokens() {
        return auth == Auth.PAT || auth == Auth.JWT_OR_PAT;
    }

    public static RouteRule from(Struct metadata) {
        if (metadata.getFieldsCount() == 0) {
            return new RouteRule("", Auth.DENY, List.of(), null, false, List.of(), List.of(), RouteBy.LOCAL);
        }
        return new RouteRule(
                string(metadata, "tenant", ""),
                enumValue(Auth.class, string(metadata, "auth", "deny"), Auth.DENY),
                strings(metadata, "scopes"),
                blankToNull(string(metadata, "hook", "")),
                metadata.getFieldsOrDefault("hook_fail_open", Value.getDefaultInstance()).getBoolValue(),
                strings(metadata, "regions"),
                strings(metadata, "backends"),
                enumValue(RouteBy.class, string(metadata, "route_by", "local"), RouteBy.LOCAL));
    }

    private static String string(Struct metadata, String field, String fallback) {
        Value value = metadata.getFieldsOrDefault(field, null);
        return value != null && value.hasStringValue() ? value.getStringValue() : fallback;
    }

    private static List<String> strings(Struct metadata, String field) {
        Value value = metadata.getFieldsOrDefault(field, null);
        if (value == null || !value.hasListValue()) {
            return List.of();
        }
        return value.getListValue().getValuesList().stream()
                .filter(Value::hasStringValue)
                .map(Value::getStringValue)
                .toList();
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String raw, E fallback) {
        try {
            return Enum.valueOf(type, raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    private static @Nullable String blankToNull(String value) {
        return value.isBlank() ? null : value;
    }
}
