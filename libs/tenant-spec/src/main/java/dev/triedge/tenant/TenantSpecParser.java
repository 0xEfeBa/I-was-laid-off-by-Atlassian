package dev.triedge.tenant;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

public final class TenantSpecParser {

    public static final int MAX_DOCUMENT_BYTES = 64 * 1024;

    public sealed interface Result {

        record Valid(TenantSpec spec, String canonicalJson, String hash) implements Result {
        }

        record Invalid(List<String> errors) implements Result {
        }
    }

    private static final YAMLMapper YAML = YAMLMapper.builder().build();
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Schema SCHEMA = loadSchema();

    private TenantSpecParser() {
    }

    public static Result parse(String document) {
        if (document.getBytes(StandardCharsets.UTF_8).length > MAX_DOCUMENT_BYTES) {
            return new Result.Invalid(List.of("Tenant definition is larger than " + MAX_DOCUMENT_BYTES + " bytes"));
        }
        JsonNode node;
        try {
            node = YAML.readTree(document);
        } catch (JacksonException e) {
            return new Result.Invalid(List.of("Tenant definition is not valid YAML or JSON: " + e.getOriginalMessage()));
        }
        return parse(node);
    }

    public static Result parse(@Nullable JsonNode node) {
        if (node == null || !node.isObject()) {
            return new Result.Invalid(List.of("Tenant definition must be an object"));
        }
        List<String> errors = new ArrayList<>();
        for (Error error : SCHEMA.validate(node)) {
            errors.add(error.getInstanceLocation() + ": " + error.getMessage());
        }
        if (!errors.isEmpty()) {
            return new Result.Invalid(errors);
        }
        TenantSpec spec = toSpec(node);
        errors.addAll(semanticErrors(spec));
        if (!errors.isEmpty()) {
            return new Result.Invalid(errors);
        }
        String canonical = canonicalJson(spec);
        return new Result.Valid(spec, canonical, sha256(canonical));
    }

    public static String canonicalJson(TenantSpec spec) {
        ObjectNode root = JSON.createObjectNode();
        root.put("apiVersion", "tri-edge/v1");
        root.put("name", spec.name());
        strings(root.putArray("regions"), spec.regions());
        strings(root.putArray("hosts"), spec.hosts());
        ArrayNode backends = root.putArray("backends");
        for (TenantSpec.Backend backend : spec.backends()) {
            backends.addObject().put("name", backend.name()).put("address", backend.address());
        }
        ArrayNode routes = root.putArray("routes");
        for (TenantSpec.Route route : spec.routes()) {
            ObjectNode r = routes.addObject();
            r.put("path", route.path());
            r.put("match", route.match().name().toLowerCase(Locale.ROOT));
            if (!route.methods().isEmpty()) {
                strings(r.putArray("methods"), route.methods());
            }
            r.put("backend", route.backend());
            r.put("auth", route.auth().name().toLowerCase(Locale.ROOT));
            strings(r.putArray("scopes"), route.scopes());
            r.put("routeBy", route.routeBy().specName());
            if (route.timeoutSeconds() != null) {
                r.put("timeoutSeconds", route.timeoutSeconds());
            }
            if (route.rateLimit() != null) {
                r.putObject("rateLimit")
                        .put("requests", route.rateLimit().requests())
                        .put("per", route.rateLimit().per().name().toLowerCase(Locale.ROOT));
            }
        }
        if (spec.hook() != null) {
            root.putObject("hook").put("address", spec.hook().address()).put("failOpen", spec.hook().failOpen());
        }
        return JSON.writeValueAsString(root);
    }

    private static TenantSpec toSpec(JsonNode node) {
        List<TenantSpec.Backend> backends = new ArrayList<>();
        for (JsonNode backend : node.get("backends")) {
            String[] address = splitAddress(backend.get("address").asString());
            backends.add(new TenantSpec.Backend(backend.get("name").asString(), address[0], Integer.parseInt(address[1])));
        }
        List<TenantSpec.Route> routes = new ArrayList<>();
        for (JsonNode route : node.get("routes")) {
            JsonNode rateLimit = route.get("rateLimit");
            JsonNode timeout = route.get("timeoutSeconds");
            routes.add(new TenantSpec.Route(
                    route.get("path").asString(),
                    enumOf(TenantSpec.Match.class, text(route, "match", "prefix")),
                    strings(route.get("methods")),
                    route.get("backend").asString(),
                    enumOf(TenantSpec.Auth.class, text(route, "auth", "none")),
                    strings(route.get("scopes")),
                    enumOf(TenantSpec.RouteBy.class, text(route, "routeBy", "local")),
                    timeout == null ? null : timeout.asInt(),
                    rateLimit == null ? null : new TenantSpec.RateLimit(
                            rateLimit.get("requests").asInt(),
                            enumOf(TenantSpec.RateLimit.Unit.class, rateLimit.get("per").asString()))));
        }
        JsonNode hook = node.get("hook");
        TenantSpec.Hook parsedHook = null;
        if (hook != null) {
            String[] address = splitAddress(hook.get("address").asString());
            JsonNode failOpen = hook.get("failOpen");
            parsedHook = new TenantSpec.Hook(address[0], Integer.parseInt(address[1]),
                    failOpen != null && failOpen.asBoolean());
        }
        List<String> regions = strings(node.get("regions"));
        return new TenantSpec(
                node.get("name").asString(),
                regions.isEmpty() ? TenantSpec.ALL_REGIONS : regions,
                strings(node.get("hosts")),
                List.copyOf(backends),
                List.copyOf(routes),
                parsedHook);
    }

    private static List<String> semanticErrors(TenantSpec spec) {
        List<String> errors = new ArrayList<>();
        Set<String> backendNames = new HashSet<>();
        for (TenantSpec.Backend backend : spec.backends()) {
            if (!backendNames.add(backend.name())) {
                errors.add("/backends: backend '" + backend.name() + "' is defined more than once");
            }
            if (backend.port() < 1 || backend.port() > 65535) {
                errors.add("/backends: port of '" + backend.name() + "' must be between 1 and 65535");
            }
        }
        Set<String> routeKeys = new HashSet<>();
        for (int i = 0; i < spec.routes().size(); i++) {
            TenantSpec.Route route = spec.routes().get(i);
            String location = "/routes/" + i;
            if (!backendNames.contains(route.backend())) {
                errors.add(location + ": unknown backend '" + route.backend() + "'");
            }
            if (route.path().equals("/internal") || route.path().startsWith("/internal/")) {
                errors.add(location + ": paths under /internal are reserved by the platform");
            }
            if (route.auth() == TenantSpec.Auth.NONE && !route.scopes().isEmpty()) {
                errors.add(location + ": scopes need an authenticated route");
            }
            if (!routeKeys.add(route.match() + " " + route.path() + " " + route.methods())) {
                errors.add(location + ": duplicate route for " + route.path());
            }
        }
        if (spec.hook() != null && (spec.hook().port() < 1 || spec.hook().port() > 65535)) {
            errors.add("/hook: port must be between 1 and 65535");
        }
        return errors;
    }

    private static String[] splitAddress(String address) {
        int colon = address.lastIndexOf(':');
        return new String[] {address.substring(0, colon), address.substring(colon + 1)};
    }

    private static String text(JsonNode node, String field, String fallback) {
        JsonNode value = node.get(field);
        return value == null ? fallback : value.asString();
    }

    private static List<String> strings(@Nullable JsonNode array) {
        if (array == null) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        array.forEach(value -> values.add(value.asString()));
        return List.copyOf(values);
    }

    private static void strings(ArrayNode target, List<String> values) {
        values.forEach(target::add);
    }

    private static <E extends Enum<E>> E enumOf(Class<E> type, String value) {
        return Enum.valueOf(type, value.toUpperCase(Locale.ROOT));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Schema loadSchema() {
        try (InputStream in = TenantSpecParser.class.getResourceAsStream("tenant-spec.v1.json")) {
            if (in == null) {
                throw new IllegalStateException("tenant-spec.v1.json is missing from the classpath");
            }
            return SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
