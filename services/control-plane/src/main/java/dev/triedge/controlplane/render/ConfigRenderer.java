package dev.triedge.controlplane.render;

import com.google.protobuf.Any;
import com.google.protobuf.BoolValue;
import com.google.protobuf.Duration;
import com.google.protobuf.ListValue;
import com.google.protobuf.Struct;
import com.google.protobuf.UInt32Value;
import com.google.protobuf.Value;
import dev.triedge.controlplane.source.ActiveTenant;
import dev.triedge.tenant.TenantSpec;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.core.v3.Address;
import io.envoyproxy.envoy.config.core.v3.AggregatedConfigSource;
import io.envoyproxy.envoy.config.core.v3.ApiVersion;
import io.envoyproxy.envoy.config.core.v3.ConfigSource;
import io.envoyproxy.envoy.config.core.v3.GrpcService;
import io.envoyproxy.envoy.config.core.v3.HeaderValue;
import io.envoyproxy.envoy.config.core.v3.HeaderValueOption;
import io.envoyproxy.envoy.config.core.v3.Http2ProtocolOptions;
import io.envoyproxy.envoy.config.core.v3.Metadata;
import io.envoyproxy.envoy.config.core.v3.RuntimeFractionalPercent;
import io.envoyproxy.envoy.config.core.v3.SocketAddress;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.endpoint.v3.Endpoint;
import io.envoyproxy.envoy.config.endpoint.v3.LbEndpoint;
import io.envoyproxy.envoy.config.endpoint.v3.LocalityLbEndpoints;
import io.envoyproxy.envoy.config.listener.v3.Filter;
import io.envoyproxy.envoy.config.listener.v3.FilterChain;
import io.envoyproxy.envoy.config.listener.v3.Listener;
import io.envoyproxy.envoy.config.route.v3.DirectResponseAction;
import io.envoyproxy.envoy.config.route.v3.RateLimit;
import io.envoyproxy.envoy.config.route.v3.HeaderMatcher;
import io.envoyproxy.envoy.config.route.v3.Route;
import io.envoyproxy.envoy.config.route.v3.RouteAction;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;
import io.envoyproxy.envoy.config.route.v3.RouteMatch;
import io.envoyproxy.envoy.config.route.v3.VirtualHost;
import io.envoyproxy.envoy.extensions.common.ratelimit.v3.LocalRateLimitDescriptor;
import io.envoyproxy.envoy.extensions.common.ratelimit.v3.RateLimitDescriptor;
import io.envoyproxy.envoy.extensions.common.ratelimit.v3.XRateLimitHeadersRFCVersion;
import io.envoyproxy.envoy.extensions.filters.http.ext_authz.v3.ExtAuthz;
import io.envoyproxy.envoy.extensions.filters.http.ext_authz.v3.ExtAuthzPerRoute;
import io.envoyproxy.envoy.extensions.filters.http.local_ratelimit.v3.LocalRateLimit;
import io.envoyproxy.envoy.extensions.filters.http.router.v3.Router;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpFilter;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.Rds;
import io.envoyproxy.envoy.extensions.upstreams.http.v3.HttpProtocolOptions;
import io.envoyproxy.envoy.type.matcher.v3.RegexMatcher;
import io.envoyproxy.envoy.type.v3.FractionalPercent;
import io.envoyproxy.envoy.type.v3.HttpStatus;
import io.envoyproxy.envoy.type.v3.StatusCode;
import io.envoyproxy.envoy.type.v3.TokenBucket;
import io.envoyproxy.envoy.type.matcher.v3.StringMatcher;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

public class ConfigRenderer {

    public static final String LISTENER = "public";
    public static final String ROUTE_CONFIGURATION = "public";
    public static final String SIDECAR_CLUSTER = "platform.edge-sidecar";
    public static final String AUTH_METADATA = "triedge.auth";
    public static final String TARGET_REGION_HEADER = "x-target-region";

    private static final String EXT_AUTHZ = "envoy.filters.http.ext_authz";
    private static final String LOCAL_RATE_LIMIT = "envoy.filters.http.local_ratelimit";
    private static final String RATE_LIMIT_STATS = "rate_limit";
    private static final int MAX_CLIENTS_PER_ROUTE = 10_000;
    private static final Duration DEFAULT_TIMEOUT = seconds(15);

    private final String region;
    private final int listenerPort;
    private final String sidecarHost;
    private final int sidecarPort;
    private final Map<String, String> remoteRegions;

    public ConfigRenderer(String region, int listenerPort, String sidecarHost, int sidecarPort) {
        this(region, listenerPort, sidecarHost, sidecarPort, Map.of());
    }

    public ConfigRenderer(String region, int listenerPort, String sidecarHost, int sidecarPort,
            Map<String, String> remoteRegions) {
        this.region = region;
        this.listenerPort = listenerPort;
        this.sidecarHost = sidecarHost;
        this.sidecarPort = sidecarPort;
        this.remoteRegions = new TreeMap<>(remoteRegions);
        this.remoteRegions.remove(region);
    }

    public EnvoyResources render(Collection<ActiveTenant> tenants, boolean inlineRoutes) {
        List<ActiveTenant> sorted = tenants.stream().sorted(Comparator.comparing(ActiveTenant::name)).toList();
        RouteConfiguration routes = routeConfiguration(sorted);
        List<Cluster> clusters = new ArrayList<>();
        clusters.add(sidecarCluster());
        for (ActiveTenant tenant : sorted) {
            for (TenantSpec.Backend backend : tenant.spec().backends()) {
                clusters.add(backendCluster(clusterName(tenant.name(), backend.name()), backend.host(), backend.port()));
                for (String remote : remoteRegionsOf(tenant)) {
                    clusters.add(backendCluster(clusterName(tenant.name(), backend.name()) + "." + remote,
                            remoteHost(remote, backend.host()), backend.port()));
                }
            }
        }
        return new EnvoyResources(
                List.of(listener(inlineRoutes ? routes : null)),
                inlineRoutes ? List.of() : List.of(routes),
                List.copyOf(clusters));
    }

    public static String clusterName(String tenant, String backend) {
        return "tenant." + tenant + "." + backend;
    }

    RouteConfiguration routeConfiguration(List<ActiveTenant> tenants) {
        RouteConfiguration.Builder config = RouteConfiguration.newBuilder()
                .setName(ROUTE_CONFIGURATION)
                .setValidateClusters(BoolValue.of(false))
                .addResponseHeadersToAdd(HeaderValueOption.newBuilder()
                        .setHeader(HeaderValue.newBuilder().setKey("x-served-by-region").setValue(region))
                        .setAppendAction(HeaderValueOption.HeaderAppendAction.OVERWRITE_IF_EXISTS_OR_ADD));
        for (ActiveTenant tenant : tenants) {
            VirtualHost.Builder host = VirtualHost.newBuilder()
                    .setName("tenant." + tenant.name())
                    .addAllDomains(tenant.spec().hosts())
                    .addRoutes(notFound("/internal/"));
            for (TenantSpec.Route route : tenant.spec().routes()) {
                if (route.routeBy() == TenantSpec.RouteBy.HOME) {
                    for (String remote : remoteRegionsOf(tenant)) {
                        host.addRoutes(route(tenant, route, remote));
                    }
                }
                host.addRoutes(route(tenant, route, null));
            }
            config.addVirtualHosts(host);
        }
        config.addVirtualHosts(VirtualHost.newBuilder().setName("unknown-host").addDomains("*").addRoutes(notFound("/")));
        return config.build();
    }

    private List<String> remoteRegionsOf(ActiveTenant tenant) {
        if (!tenant.system()) {
            return List.of();
        }
        return remoteRegions.keySet().stream().filter(tenant.spec().regions()::contains).toList();
    }

    private String remoteHost(String remote, String backendHost) {
        String template = remoteRegions.get(remote);
        return template.contains("{host}") ? template.replace("{host}", backendHost) : template;
    }

    private Route route(ActiveTenant tenant, TenantSpec.Route route, @Nullable String remote) {
        RouteMatch.Builder match = RouteMatch.newBuilder();
        if (route.match() == TenantSpec.Match.EXACT) {
            match.setPath(route.path());
        } else {
            match.setPrefix(route.path());
        }
        if (!route.methods().isEmpty()) {
            match.addHeaders(HeaderMatcher.newBuilder()
                    .setName(":method")
                    .setStringMatch(StringMatcher.newBuilder().setSafeRegex(RegexMatcher.newBuilder()
                            .setRegex(String.join("|", route.methods())))));
        }
        String cluster = clusterName(tenant.name(), route.backend());
        if (remote != null) {
            match.addHeaders(HeaderMatcher.newBuilder()
                    .setName(TARGET_REGION_HEADER)
                    .setStringMatch(StringMatcher.newBuilder().setExact(remote)));
            cluster = cluster + "." + remote;
        }
        RouteAction.Builder action = RouteAction.newBuilder()
                .setCluster(cluster)
                .setTimeout(route.timeoutSeconds() == null ? DEFAULT_TIMEOUT : seconds(route.timeoutSeconds()));
        Route.Builder built = Route.newBuilder()
                .setMatch(match)
                .setRoute(action)
                .setMetadata(Metadata.newBuilder().putFilterMetadata(AUTH_METADATA, authRule(tenant, route)));
        if (route.rateLimit() != null) {
            built.putTypedPerFilterConfig(LOCAL_RATE_LIMIT, Any.pack(perClientLimit(route.rateLimit())));
        }
        return built.build();
    }

    private static LocalRateLimit perClientLimit(TenantSpec.RateLimit limit) {
        TokenBucket bucket = TokenBucket.newBuilder()
                .setMaxTokens(limit.requests())
                .setTokensPerFill(UInt32Value.of(limit.requests()))
                .setFillInterval(seconds(intervalSeconds(limit.per())))
                .build();
        return LocalRateLimit.newBuilder()
                .setStatPrefix(RATE_LIMIT_STATS)
                .setTokenBucket(bucket)
                .setAlwaysConsumeDefaultTokenBucket(BoolValue.of(false))
                .setFilterEnabled(always("rate_limit.enabled"))
                .setFilterEnforced(always("rate_limit.enforced"))
                .addRateLimits(RateLimit.newBuilder().addActions(RateLimit.Action.newBuilder()
                        .setRemoteAddress(RateLimit.Action.RemoteAddress.getDefaultInstance())))
                .addDescriptors(LocalRateLimitDescriptor.newBuilder()
                        .addEntries(RateLimitDescriptor.Entry.newBuilder().setKey("remote_address"))
                        .setTokenBucket(bucket))
                .setMaxDynamicDescriptors(UInt32Value.of(MAX_CLIENTS_PER_ROUTE))
                .setEnableXRatelimitHeaders(XRateLimitHeadersRFCVersion.DRAFT_VERSION_03)
                .build();
    }

    private static long intervalSeconds(TenantSpec.RateLimit.Unit unit) {
        return switch (unit) {
            case SECOND -> 1;
            case MINUTE -> 60;
            case HOUR -> 3600;
        };
    }

    private static RuntimeFractionalPercent always(String runtimeKey) {
        return RuntimeFractionalPercent.newBuilder()
                .setDefaultValue(FractionalPercent.newBuilder()
                        .setNumerator(100)
                        .setDenominator(FractionalPercent.DenominatorType.HUNDRED))
                .setRuntimeKey(runtimeKey)
                .build();
    }

    private static Struct authRule(ActiveTenant tenant, TenantSpec.Route route) {
        TenantSpec spec = tenant.spec();
        Struct.Builder rule = Struct.newBuilder()
                .putFields("tenant", string(tenant.name()))
                .putFields("auth", string(route.auth().sidecarName()))
                .putFields("scopes", list(route.scopes()))
                .putFields("regions", list(spec.regions()))
                .putFields("backends", list(spec.backends().stream().map(TenantSpec.Backend::name).toList()))
                .putFields("route_by", string(route.routeBy().specName()));
        if (spec.hook() != null) {
            rule.putFields("hook", string(spec.hook().address()))
                    .putFields("hook_fail_open", Value.newBuilder().setBoolValue(spec.hook().failOpen()).build());
        }
        return rule.build();
    }

    private static Route notFound(String prefix) {
        return Route.newBuilder()
                .setMatch(RouteMatch.newBuilder().setPrefix(prefix))
                .setDirectResponse(DirectResponseAction.newBuilder().setStatus(404))
                .putTypedPerFilterConfig(EXT_AUTHZ, Any.pack(ExtAuthzPerRoute.newBuilder().setDisabled(true).build()))
                .build();
    }

    private Listener listener(@Nullable RouteConfiguration inlineRoutes) {
        HttpConnectionManager.Builder manager = HttpConnectionManager.newBuilder()
                .setStatPrefix(LISTENER)
                .setCodecType(HttpConnectionManager.CodecType.AUTO)
                .setStripAnyHostPort(true)
                .setUseRemoteAddress(BoolValue.of(true))
                .addHttpFilters(HttpFilter.newBuilder().setName(LOCAL_RATE_LIMIT).setTypedConfig(Any.pack(
                        LocalRateLimit.newBuilder().setStatPrefix(RATE_LIMIT_STATS).build())))
                .addHttpFilters(HttpFilter.newBuilder().setName(EXT_AUTHZ).setTypedConfig(Any.pack(ExtAuthz.newBuilder()
                        .setTransportApiVersion(ApiVersion.V3)
                        .setFailureModeAllow(false)
                        .setStatusOnError(HttpStatus.newBuilder().setCode(StatusCode.ServiceUnavailable))
                        .setClearRouteCache(true)
                        .addRouteMetadataContextNamespaces(AUTH_METADATA)
                        .setGrpcService(GrpcService.newBuilder()
                                .setEnvoyGrpc(GrpcService.EnvoyGrpc.newBuilder().setClusterName(SIDECAR_CLUSTER))
                                .setTimeout(Duration.newBuilder().setNanos(250_000_000)))
                        .build())))
                .addHttpFilters(HttpFilter.newBuilder()
                        .setName("envoy.filters.http.router")
                        .setTypedConfig(Any.pack(Router.getDefaultInstance())));
        if (inlineRoutes != null) {
            manager.setRouteConfig(inlineRoutes);
        } else {
            manager.setRds(Rds.newBuilder()
                    .setRouteConfigName(ROUTE_CONFIGURATION)
                    .setConfigSource(ConfigSource.newBuilder()
                            .setAds(AggregatedConfigSource.getDefaultInstance())
                            .setResourceApiVersion(ApiVersion.V3)));
        }
        return Listener.newBuilder()
                .setName(LISTENER)
                .setAddress(Address.newBuilder().setSocketAddress(SocketAddress.newBuilder()
                        .setAddress("0.0.0.0")
                        .setPortValue(listenerPort)))
                .addFilterChains(FilterChain.newBuilder().addFilters(Filter.newBuilder()
                        .setName("envoy.filters.network.http_connection_manager")
                        .setTypedConfig(Any.pack(manager.build()))))
                .build();
    }

    private Cluster sidecarCluster() {
        return backendCluster(SIDECAR_CLUSTER, sidecarHost, sidecarPort).toBuilder()
                .putTypedExtensionProtocolOptions("envoy.extensions.upstreams.http.v3.HttpProtocolOptions",
                        Any.pack(HttpProtocolOptions.newBuilder()
                                .setExplicitHttpConfig(HttpProtocolOptions.ExplicitHttpConfig.newBuilder()
                                        .setHttp2ProtocolOptions(Http2ProtocolOptions.getDefaultInstance()))
                                .build()))
                .build();
    }

    private static Cluster backendCluster(String name, String host, int port) {
        return Cluster.newBuilder()
                .setName(name)
                .setType(Cluster.DiscoveryType.STRICT_DNS)
                .setDnsLookupFamily(Cluster.DnsLookupFamily.V4_PREFERRED)
                .setConnectTimeout(seconds(2))
                .setLoadAssignment(ClusterLoadAssignment.newBuilder()
                        .setClusterName(name)
                        .addEndpoints(LocalityLbEndpoints.newBuilder().addLbEndpoints(LbEndpoint.newBuilder()
                                .setEndpoint(Endpoint.newBuilder().setAddress(Address.newBuilder()
                                        .setSocketAddress(SocketAddress.newBuilder().setAddress(host).setPortValue(port)))))))
                .build();
    }

    private static Value string(String value) {
        return Value.newBuilder().setStringValue(value).build();
    }

    private static Value list(List<String> values) {
        ListValue.Builder list = ListValue.newBuilder();
        values.forEach(value -> list.addValues(string(value)));
        return Value.newBuilder().setListValue(list).build();
    }

    private static Duration seconds(long seconds) {
        return Duration.newBuilder().setSeconds(seconds).build();
    }
}
