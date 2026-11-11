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
import io.envoyproxy.envoy.config.core.v3.BindConfig;
import io.envoyproxy.envoy.config.core.v3.CidrRange;
import io.envoyproxy.envoy.config.core.v3.ConfigSource;
import io.envoyproxy.envoy.config.core.v3.GrpcService;
import io.envoyproxy.envoy.config.core.v3.HeaderValue;
import io.envoyproxy.envoy.config.core.v3.HeaderValueOption;
import io.envoyproxy.envoy.config.core.v3.HttpService;
import io.envoyproxy.envoy.config.core.v3.HttpUri;
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
import io.envoyproxy.envoy.config.listener.v3.FilterChainMatch;
import io.envoyproxy.envoy.config.listener.v3.Listener;
import io.envoyproxy.envoy.config.route.v3.DirectResponseAction;
import io.envoyproxy.envoy.config.route.v3.RateLimit;
import io.envoyproxy.envoy.config.route.v3.HeaderMatcher;
import io.envoyproxy.envoy.config.route.v3.Route;
import io.envoyproxy.envoy.config.route.v3.RouteAction;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;
import io.envoyproxy.envoy.config.route.v3.RouteMatch;
import io.envoyproxy.envoy.config.route.v3.VirtualHost;
import io.envoyproxy.envoy.config.trace.v3.OpenTelemetryConfig;
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
import io.envoyproxy.envoy.type.v3.Percent;
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
    public static final String TRACING_CLUSTER = "platform.tracing";
    public static final String AUTH_METADATA = "triedge.auth";
    public static final String TARGET_REGION_HEADER = "x-target-region";
    public static final String HOP_HEADER = "x-triedge-hop";
    public static final String SERVED_BY_HEADER = "x-served-by-region";

    private static final String EXT_AUTHZ = "envoy.filters.http.ext_authz";
    private static final String LOCAL_RATE_LIMIT = "envoy.filters.http.local_ratelimit";
    private static final String RATE_LIMIT_STATS = "rate_limit";
    private static final int MAX_CLIENTS_PER_ROUTE = 10_000;
    private static final Duration DEFAULT_TIMEOUT = seconds(15);
    private static final int BUFFER_LIMIT_BYTES = 32 * 1024;

    private final String region;
    private final int listenerPort;
    private final String sidecarHost;
    private final int sidecarPort;
    private final Map<String, String> remoteRegions;
    private final List<String> peerCidrs;
    private final @Nullable String tenantSourceAddress;
    private final @Nullable Tracing tracing;

    public record Tracing(String host, int port, double samplingPercent) {
    }

    public ConfigRenderer(String region, int listenerPort, String sidecarHost, int sidecarPort) {
        this(region, listenerPort, sidecarHost, sidecarPort, Map.of());
    }

    public ConfigRenderer(String region, int listenerPort, String sidecarHost, int sidecarPort,
            Map<String, String> remoteRegions) {
        this(region, listenerPort, sidecarHost, sidecarPort, remoteRegions, List.of());
    }

    public ConfigRenderer(String region, int listenerPort, String sidecarHost, int sidecarPort,
            Map<String, String> remoteRegions, List<String> peerCidrs) {
        this(region, listenerPort, sidecarHost, sidecarPort, remoteRegions, peerCidrs, null);
    }

    public ConfigRenderer(String region, int listenerPort, String sidecarHost, int sidecarPort,
            Map<String, String> remoteRegions, List<String> peerCidrs, @Nullable String tenantSourceAddress) {
        this(region, listenerPort, sidecarHost, sidecarPort, remoteRegions, peerCidrs, tenantSourceAddress, null);
    }

    public ConfigRenderer(String region, int listenerPort, String sidecarHost, int sidecarPort,
            Map<String, String> remoteRegions, List<String> peerCidrs, @Nullable String tenantSourceAddress,
            @Nullable Tracing tracing) {
        this.region = region;
        this.listenerPort = listenerPort;
        this.sidecarHost = sidecarHost;
        this.sidecarPort = sidecarPort;
        this.remoteRegions = new TreeMap<>(remoteRegions);
        this.remoteRegions.remove(region);
        this.peerCidrs = List.copyOf(peerCidrs);
        this.tenantSourceAddress = tenantSourceAddress == null || tenantSourceAddress.isBlank()
                ? null
                : tenantSourceAddress;
        this.tracing = tracing;
    }

    public EnvoyResources render(Collection<ActiveTenant> tenants, boolean inlineRoutes) {
        List<ActiveTenant> sorted = tenants.stream().sorted(Comparator.comparing(ActiveTenant::name)).toList();
        RouteConfiguration routes = routeConfiguration(sorted);
        List<Cluster> clusters = new ArrayList<>();
        clusters.add(sidecarCluster());
        if (tracing != null) {
            clusters.add(backendCluster(TRACING_CLUSTER, tracing.host(), tracing.port()));
        }
        for (String remote : remoteRegions.keySet()) {
            clusters.add(backendCluster(edgeCluster(remote), remoteHost(remote, "envoy"), listenerPort));
        }
        for (ActiveTenant tenant : sorted) {
            if (!tenant.system() && !tenant.spec().regions().contains(region)) {
                continue;
            }
            for (TenantSpec.Backend backend : tenant.spec().backends()) {
                Cluster cluster = backendCluster(clusterName(tenant.name(), backend.name()), backend.host(), backend.port());
                clusters.add(tenant.system() ? cluster : userCluster(cluster));
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

    public static String edgeCluster(String region) {
        return "edge." + region;
    }

    RouteConfiguration routeConfiguration(List<ActiveTenant> tenants) {
        RouteConfiguration.Builder config = RouteConfiguration.newBuilder()
                .setName(ROUTE_CONFIGURATION)
                .setValidateClusters(BoolValue.of(false))
                .addResponseHeadersToAdd(servedBy(HeaderValueOption.HeaderAppendAction.ADD_IF_ABSENT));
        for (ActiveTenant tenant : tenants) {
            VirtualHost.Builder host = VirtualHost.newBuilder()
                    .setName("tenant." + tenant.name())
                    .addAllDomains(tenant.spec().hosts());
            if (tenant.system()) {
                host.addRoutes(notFound("/internal/"));
                addSystemRoutes(host, tenant);
            } else {
                addUserRoutes(host, tenant);
            }
            config.addVirtualHosts(host);
        }
        config.addVirtualHosts(VirtualHost.newBuilder().setName("unknown-host").addDomains("*").addRoutes(notFound("/")));
        return config.build();
    }

    private void addSystemRoutes(VirtualHost.Builder host, ActiveTenant tenant) {
        for (TenantSpec.Route route : tenant.spec().routes()) {
            String cluster = clusterName(tenant.name(), route.backend());
            if (route.routeBy() == TenantSpec.RouteBy.HOME) {
                for (String remote : remoteRegionsOf(tenant)) {
                    host.addRoutes(route(tenant, route, remote, cluster + "." + remote, false));
                }
            }
            host.addRoutes(route(tenant, route, null, cluster, false));
        }
    }

    private void addUserRoutes(VirtualHost.Builder host, ActiveTenant tenant) {
        List<String> remotes = tenant.spec().regions().stream().filter(remoteRegions::containsKey).toList();
        boolean servedHere = tenant.spec().regions().contains(region);
        if (!servedHere && remotes.isEmpty()) {
            host.addRoutes(directResponse("/", 503));
            return;
        }
        for (TenantSpec.Route route : tenant.spec().routes()) {
            if (!servedHere) {
                host.addRoutes(route(tenant, route, null, edgeCluster(remotes.getFirst()), true));
                continue;
            }
            if (route.routeBy() == TenantSpec.RouteBy.HOME) {
                for (String remote : remotes) {
                    host.addRoutes(route(tenant, route, remote, edgeCluster(remote), true));
                }
            }
            host.addRoutes(route(tenant, route, null, clusterName(tenant.name(), route.backend()), false));
        }
        if (!servedHere) {
            host.addRoutes(directResponse("/", 508));
        }
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

    private Route route(ActiveTenant tenant, TenantSpec.Route route, @Nullable String targetRegion, String cluster,
            boolean forwarded) {
        RouteMatch.Builder match = RouteMatch.newBuilder();
        if (route.match() == TenantSpec.Match.EXACT) {
            match.setPath(route.path());
        } else if (route.path().endsWith("/")) {
            match.setPrefix(route.path());
        } else {
            match.setPathSeparatedPrefix(route.path());
        }
        if (!route.methods().isEmpty()) {
            match.addHeaders(HeaderMatcher.newBuilder()
                    .setName(":method")
                    .setStringMatch(StringMatcher.newBuilder().setSafeRegex(RegexMatcher.newBuilder()
                            .setRegex(String.join("|", route.methods())))));
        }
        if (targetRegion != null) {
            match.addHeaders(HeaderMatcher.newBuilder()
                    .setName(TARGET_REGION_HEADER)
                    .setStringMatch(StringMatcher.newBuilder().setExact(targetRegion)));
        }
        if (forwarded) {
            match.addHeaders(HeaderMatcher.newBuilder().setName(HOP_HEADER).setPresentMatch(true).setInvertMatch(true));
        }
        RouteAction.Builder action = RouteAction.newBuilder()
                .setCluster(cluster)
                .setTimeout(route.timeoutSeconds() == null ? DEFAULT_TIMEOUT : seconds(route.timeoutSeconds()));
        Route.Builder built = Route.newBuilder()
                .setMatch(match)
                .setRoute(action)
                .setMetadata(Metadata.newBuilder().putFilterMetadata(AUTH_METADATA, authRule(tenant, route)));
        if (forwarded) {
            built.addRequestHeadersToAdd(HeaderValueOption.newBuilder()
                    .setHeader(HeaderValue.newBuilder().setKey(HOP_HEADER).setValue(region))
                    .setAppendAction(HeaderValueOption.HeaderAppendAction.OVERWRITE_IF_EXISTS_OR_ADD));
        } else {
            built.addResponseHeadersToAdd(servedBy(HeaderValueOption.HeaderAppendAction.OVERWRITE_IF_EXISTS_OR_ADD));
        }
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

    private HeaderValueOption servedBy(HeaderValueOption.HeaderAppendAction action) {
        return HeaderValueOption.newBuilder()
                .setHeader(HeaderValue.newBuilder().setKey(SERVED_BY_HEADER).setValue(region))
                .setAppendAction(action)
                .build();
    }

    private static Route notFound(String prefix) {
        return directResponse(prefix, 404);
    }

    private static Route directResponse(String prefix, int status) {
        return Route.newBuilder()
                .setMatch(RouteMatch.newBuilder().setPrefix(prefix))
                .setDirectResponse(DirectResponseAction.newBuilder().setStatus(status))
                .putTypedPerFilterConfig(EXT_AUTHZ, Any.pack(ExtAuthzPerRoute.newBuilder().setDisabled(true).build()))
                .build();
    }

    private Listener listener(@Nullable RouteConfiguration inlineRoutes) {
        Listener.Builder listener = Listener.newBuilder()
                .setName(LISTENER)
                .setAddress(Address.newBuilder().setSocketAddress(SocketAddress.newBuilder()
                        .setAddress("0.0.0.0")
                        .setPortValue(listenerPort)))
                .setPerConnectionBufferLimitBytes(UInt32Value.of(BUFFER_LIMIT_BYTES))
                .addFilterChains(filterChain(manager(inlineRoutes, 0)));
        if (!peerCidrs.isEmpty()) {
            FilterChainMatch.Builder peers = FilterChainMatch.newBuilder();
            for (String cidr : peerCidrs) {
                String[] parts = cidr.split("/", 2);
                peers.addSourcePrefixRanges(CidrRange.newBuilder()
                        .setAddressPrefix(parts[0])
                        .setPrefixLen(UInt32Value.of(parts.length == 2 ? Integer.parseInt(parts[1]) : 32)));
            }
            listener.addFilterChains(filterChain(manager(inlineRoutes, 1)).toBuilder().setFilterChainMatch(peers));
        }
        return listener.build();
    }

    private static FilterChain filterChain(HttpConnectionManager manager) {
        return FilterChain.newBuilder().addFilters(Filter.newBuilder()
                        .setName("envoy.filters.network.http_connection_manager")
                        .setTypedConfig(Any.pack(manager)))
                .build();
    }

    private HttpConnectionManager manager(@Nullable RouteConfiguration inlineRoutes, int trustedHops) {
        HttpConnectionManager.Builder manager = HttpConnectionManager.newBuilder()
                .setStatPrefix(LISTENER)
                .setCodecType(HttpConnectionManager.CodecType.AUTO)
                .setStripAnyHostPort(true)
                .setUseRemoteAddress(BoolValue.of(true))
                .setXffNumTrustedHops(trustedHops)
                .setNormalizePath(BoolValue.of(true))
                .setMergeSlashes(true)
                .setPathWithEscapedSlashesAction(HttpConnectionManager.PathWithEscapedSlashesAction.REJECT_REQUEST)
                .setRequestHeadersTimeout(seconds(10))
                .setStreamIdleTimeout(seconds(300))
                .setCommonHttpProtocolOptions(io.envoyproxy.envoy.config.core.v3.HttpProtocolOptions.newBuilder()
                        .setIdleTimeout(seconds(3600))
                        .setHeadersWithUnderscoresAction(
                                io.envoyproxy.envoy.config.core.v3.HttpProtocolOptions.HeadersWithUnderscoresAction
                                        .REJECT_REQUEST))
                .setHttp2ProtocolOptions(Http2ProtocolOptions.newBuilder()
                        .setMaxConcurrentStreams(UInt32Value.of(100))
                        .setInitialStreamWindowSize(UInt32Value.of(64 * 1024))
                        .setInitialConnectionWindowSize(UInt32Value.of(1024 * 1024)))
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
        if (tracing != null) {
            manager.setTracing(HttpConnectionManager.Tracing.newBuilder()
                    .setRandomSampling(Percent.newBuilder().setValue(tracing.samplingPercent()))
                    .setProvider(io.envoyproxy.envoy.config.trace.v3.Tracing.Http.newBuilder()
                            .setName("envoy.tracers.opentelemetry")
                            .setTypedConfig(Any.pack(OpenTelemetryConfig.newBuilder()
                                    .setServiceName("envoy")
                                    .setHttpService(HttpService.newBuilder().setHttpUri(HttpUri.newBuilder()
                                            .setUri("http://" + tracing.host() + ":" + tracing.port() + "/v1/traces")
                                            .setCluster(TRACING_CLUSTER)
                                            .setTimeout(seconds(1))))
                                    .build()))));
        }
        if (inlineRoutes != null) {
            manager.setRouteConfig(inlineRoutes);
        } else {
            manager.setRds(Rds.newBuilder()
                    .setRouteConfigName(ROUTE_CONFIGURATION)
                    .setConfigSource(ConfigSource.newBuilder()
                            .setAds(AggregatedConfigSource.getDefaultInstance())
                            .setResourceApiVersion(ApiVersion.V3)));
        }
        return manager.build();
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

    private Cluster userCluster(Cluster cluster) {
        Cluster.Builder user = cluster.toBuilder().setDnsLookupFamily(Cluster.DnsLookupFamily.V4_ONLY);
        if (tenantSourceAddress != null) {
            user.setUpstreamBindConfig(BindConfig.newBuilder().setSourceAddress(SocketAddress.newBuilder()
                    .setAddress(tenantSourceAddress)
                    .setPortValue(0)));
        }
        return user.build();
    }

    private static Cluster backendCluster(String name, String host, int port) {
        return Cluster.newBuilder()
                .setName(name)
                .setPerConnectionBufferLimitBytes(UInt32Value.of(BUFFER_LIMIT_BYTES))
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
