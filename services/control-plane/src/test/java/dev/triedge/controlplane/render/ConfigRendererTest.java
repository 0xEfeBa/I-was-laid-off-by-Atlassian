package dev.triedge.controlplane.render;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import dev.triedge.controlplane.TestTenants;
import dev.triedge.controlplane.source.ActiveTenant;
import dev.triedge.controlplane.validate.BootstrapJson;
import dev.triedge.tenant.TenantSpec;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.route.v3.HeaderMatcher;
import io.envoyproxy.envoy.config.route.v3.Route;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;
import io.envoyproxy.envoy.config.route.v3.VirtualHost;
import io.envoyproxy.envoy.extensions.common.ratelimit.v3.LocalRateLimitDescriptor;
import io.envoyproxy.envoy.extensions.filters.http.ext_authz.v3.ExtAuthz;
import io.envoyproxy.envoy.extensions.filters.http.local_ratelimit.v3.LocalRateLimit;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager;
import io.envoyproxy.envoy.type.v3.StatusCode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ConfigRendererTest {

    private final ConfigRenderer renderer = new ConfigRenderer("eu", 8080, "edge-sidecar", 9001);

    private final TenantSpec shop = new TenantSpec("shop", List.of("eu", "us"), List.of("shop.test"),
            List.of(new TenantSpec.Backend("api", "api.t-shop.svc", 9000)),
            List.of(
                    new TenantSpec.Route("/orders", TenantSpec.Match.PREFIX, List.of("GET", "HEAD"), "api",
                            TenantSpec.Auth.ANY, List.of("edge:read"), TenantSpec.RouteBy.HOME, 5, null),
                    new TenantSpec.Route("/health", TenantSpec.Match.EXACT, List.of(), "api",
                            TenantSpec.Auth.NONE, List.of(), TenantSpec.RouteBy.LOCAL, null, null)),
            new TenantSpec.Hook("authz.t-shop.svc", 9002, true));

    @Test
    void everyTenantGetsAVirtualHostWithInternalPathsBlockedFirst() {
        RouteConfiguration routes = routes(List.of(TestTenants.tenant("shop", 3, shop),
                TestTenants.tenant("blog", "blog.test", "blog.t-blog.svc", 80)));

        assertThat(routes.getVirtualHostsList()).extracting(VirtualHost::getName)
                .containsExactly("tenant.blog", "tenant.shop", "unknown-host");
        VirtualHost host = routes.getVirtualHosts(1);
        assertThat(host.getDomainsList()).containsExactly("shop.test");
        assertThat(host.getRoutes(0).getMatch().getPrefix()).isEqualTo("/internal/");
        assertThat(host.getRoutes(0).getDirectResponse().getStatus()).isEqualTo(404);
        assertThat(host.getRoutes(0).getTypedPerFilterConfigMap()).containsKey("envoy.filters.http.ext_authz");
        assertThat(routes.getVirtualHosts(2).getDomainsList()).containsExactly("*");
        assertThat(routes.getResponseHeadersToAdd(0).getHeader().getValue()).isEqualTo("eu");
    }

    @Test
    void routesCarryMatchersTimeoutsAndTheSidecarRule() {
        VirtualHost host = routes(List.of(TestTenants.tenant("shop", 3, shop))).getVirtualHosts(0);
        Route orders = host.getRoutes(1);
        Route health = host.getRoutes(2);

        assertThat(orders.getMatch().getPrefix()).isEqualTo("/orders");
        assertThat(orders.getMatch().getHeaders(0).getName()).isEqualTo(":method");
        assertThat(orders.getMatch().getHeaders(0).getStringMatch().getSafeRegex().getRegex()).isEqualTo("GET|HEAD");
        assertThat(orders.getRoute().getCluster()).isEqualTo("tenant.shop.api");
        assertThat(orders.getRoute().getTimeout().getSeconds()).isEqualTo(5);
        assertThat(health.getMatch().getPath()).isEqualTo("/health");
        assertThat(health.getRoute().getTimeout().getSeconds()).isEqualTo(15);

        Struct rule = orders.getMetadata().getFilterMetadataOrThrow(ConfigRenderer.AUTH_METADATA);
        assertThat(rule.getFieldsOrThrow("tenant").getStringValue()).isEqualTo("shop");
        assertThat(rule.getFieldsOrThrow("auth").getStringValue()).isEqualTo("jwt_or_pat");
        assertThat(rule.getFieldsOrThrow("route_by").getStringValue()).isEqualTo("home");
        assertThat(strings(rule.getFieldsOrThrow("scopes"))).containsExactly("edge:read");
        assertThat(strings(rule.getFieldsOrThrow("regions"))).containsExactly("eu", "us");
        assertThat(strings(rule.getFieldsOrThrow("backends"))).containsExactly("api");
        assertThat(rule.getFieldsOrThrow("hook").getStringValue()).isEqualTo("authz.t-shop.svc:9002");
        assertThat(rule.getFieldsOrThrow("hook_fail_open").getBoolValue()).isTrue();
    }

    @Test
    void clustersPointAtTenantBackendsAndTheSidecarSpeaksHttp2() {
        EnvoyResources resources = renderer.render(List.of(TestTenants.tenant("shop", 3, shop)), false);

        assertThat(resources.clusters()).extracting(Cluster::getName)
                .containsExactly(ConfigRenderer.SIDECAR_CLUSTER, "tenant.shop.api");
        Cluster api = resources.clusters().get(1);
        assertThat(api.getLoadAssignment().getEndpoints(0).getLbEndpoints(0).getEndpoint().getAddress()
                .getSocketAddress().getAddress()).isEqualTo("api.t-shop.svc");
        assertThat(resources.clusters().getFirst().getTypedExtensionProtocolOptionsMap())
                .containsKey("envoy.extensions.upstreams.http.v3.HttpProtocolOptions");
    }

    @Test
    void listenerUsesRdsOrInlineRoutes() throws Exception {
        EnvoyResources served = renderer.render(List.of(), false);
        EnvoyResources validated = renderer.render(List.of(), true);

        HttpConnectionManager servedManager = manager(served);
        assertThat(servedManager.getRds().getRouteConfigName()).isEqualTo(ConfigRenderer.ROUTE_CONFIGURATION);
        assertThat(servedManager.getStripAnyHostPort()).isTrue();
        assertThat(servedManager.getHttpFiltersList()).extracting(f -> f.getName())
                .containsExactly("envoy.filters.http.local_ratelimit", "envoy.filters.http.ext_authz",
                        "envoy.filters.http.router");
        assertThat(servedManager.getHttpFilters(0).getTypedConfig().unpack(LocalRateLimit.class).hasTokenBucket())
                .isFalse();
        assertThat(servedManager.getHttpFilters(1).getTypedConfig().unpack(ExtAuthz.class).getStatusOnError().getCode())
                .isEqualTo(StatusCode.ServiceUnavailable);
        assertThat(served.routes()).hasSize(1);
        assertThat(manager(validated).hasRouteConfig()).isTrue();
        assertThat(validated.routes()).isEmpty();
        assertThat(served.listeners().getFirst().getAddress().getSocketAddress().getPortValue()).isEqualTo(8080);
    }

    @Test
    void rateLimitedRoutesGetABucketPerClientAddress() throws Exception {
        TenantSpec limited = new TenantSpec("auth", List.of("eu"), List.of("auth.test"),
                List.of(new TenantSpec.Backend("api", "api.t-auth.svc", 9000)),
                List.of(new TenantSpec.Route("/login", TenantSpec.Match.EXACT, List.of("POST"), "api",
                        TenantSpec.Auth.NONE, List.of(), TenantSpec.RouteBy.LOCAL, null,
                        new TenantSpec.RateLimit(20, TenantSpec.RateLimit.Unit.MINUTE))),
                null);
        VirtualHost host = routes(List.of(TestTenants.tenant("auth", 1, limited))).getVirtualHosts(0);

        LocalRateLimit limit = host.getRoutes(1).getTypedPerFilterConfigOrThrow("envoy.filters.http.local_ratelimit")
                .unpack(LocalRateLimit.class);
        assertThat(limit.getAlwaysConsumeDefaultTokenBucket().getValue()).isFalse();
        assertThat(limit.getFilterEnabled().getDefaultValue().getNumerator()).isEqualTo(100);
        assertThat(limit.getFilterEnforced().getDefaultValue().getNumerator()).isEqualTo(100);
        assertThat(limit.getRateLimits(0).getActions(0).hasRemoteAddress()).isTrue();
        LocalRateLimitDescriptor perClient = limit.getDescriptors(0);
        assertThat(perClient.getEntries(0).getKey()).isEqualTo("remote_address");
        assertThat(perClient.getEntries(0).getValue()).isEmpty();
        assertThat(perClient.getTokenBucket().getMaxTokens()).isEqualTo(20);
        assertThat(perClient.getTokenBucket().getTokensPerFill().getValue()).isEqualTo(20);
        assertThat(perClient.getTokenBucket().getFillInterval().getSeconds()).isEqualTo(60);
        assertThat(limit.getMaxDynamicDescriptors().getValue()).isPositive();
        assertThat(host.getRoutes(0).getTypedPerFilterConfigMap()).doesNotContainKey("envoy.filters.http.local_ratelimit");
    }

    @Test
    void routesWithoutALimitAreNotLimited() {
        VirtualHost host = routes(List.of(TestTenants.tenant("shop", 3, shop))).getVirtualHosts(0);

        assertThat(host.getRoutesList()).allSatisfy(route ->
                assertThat(route.getTypedPerFilterConfigMap()).doesNotContainKey("envoy.filters.http.local_ratelimit"));
    }

    @Test
    void hostileValuesStayLiteralValues() {
        String hostile = "evil.test\"},{\"domains\":[\"*\"]";
        String backend = "x\n- name: other";
        ActiveTenant tenant = TestTenants.tenant("evil", hostile, backend, 80);

        EnvoyResources resources = renderer.render(List.of(tenant), true);
        JsonNode bootstrap = JsonMapper.builder().build().readTree(BootstrapJson.of(resources));

        assertThat(routes(List.of(tenant)).getVirtualHosts(0).getDomainsList()).containsExactly(hostile);
        assertThat(bootstrap.findValues("domains")).anySatisfy(d -> assertThat(d.get(0).asString()).isEqualTo(hostile));
        assertThat(bootstrap.findValues("socketAddress"))
                .anySatisfy(a -> assertThat(a.get("address").asString()).isEqualTo(backend));
        assertThat(bootstrap.findValues("domains")).hasSize(2);
    }

    @Test
    void homeRoutesOfPlatformTenantsCanReachOtherRegions() {
        ConfigRenderer multiRegion = new ConfigRenderer("eu", 8080, "edge-sidecar", 9001,
                Map.of("eu", "ignored", "us", "{host}.us.internal", "ap", "10.66.0.3"));
        TenantSpec platform = new TenantSpec("platform", List.of("eu", "us", "ap"), List.of("localhost"),
                List.of(new TenantSpec.Backend("broker", "broker", 8110)),
                List.of(new TenantSpec.Route("/v2/", TenantSpec.Match.PREFIX, List.of(), "broker",
                                TenantSpec.Auth.ANY, List.of(), TenantSpec.RouteBy.HOME, null, null),
                        new TenantSpec.Route("/health", TenantSpec.Match.EXACT, List.of(), "broker",
                                TenantSpec.Auth.NONE, List.of(), TenantSpec.RouteBy.LOCAL, null, null)),
                null);
        ActiveTenant system = new ActiveTenant(UUID.randomUUID(), "platform", 0, platform, true);
        ActiveTenant user = TestTenants.tenant("shop", 1, shop);

        EnvoyResources resources = multiRegion.render(List.of(system, user), false);
        VirtualHost host = resources.routes().getFirst().getVirtualHostsList().stream()
                .filter(h -> h.getName().equals("tenant.platform")).findFirst().orElseThrow();

        assertThat(host.getRoutesList()).extracting(r -> r.getRoute().getCluster())
                .containsExactly("", "tenant.platform.broker.ap", "tenant.platform.broker.us",
                        "tenant.platform.broker", "tenant.platform.broker");
        HeaderMatcher target = host.getRoutes(1).getMatch().getHeaders(0);
        assertThat(target.getName()).isEqualTo(ConfigRenderer.TARGET_REGION_HEADER);
        assertThat(target.getStringMatch().getExact()).isEqualTo("ap");
        assertThat(host.getRoutes(1).getMetadata()).isEqualTo(host.getRoutes(3).getMetadata());
        assertThat(resources.clusters()).extracting(Cluster::getName)
                .contains("tenant.platform.broker.ap", "tenant.platform.broker.us")
                .doesNotContain("tenant.platform.broker.eu", "tenant.shop.api.us");
        assertThat(resources.clusters().stream().filter(c -> c.getName().equals("tenant.platform.broker.us")).findFirst()
                .orElseThrow().getLoadAssignment().getEndpoints(0).getLbEndpoints(0).getEndpoint().getAddress()
                .getSocketAddress().getAddress()).isEqualTo("broker.us.internal");
        assertThat(resources.clusters().stream().filter(c -> c.getName().equals("tenant.platform.broker.ap")).findFirst()
                .orElseThrow().getLoadAssignment().getEndpoints(0).getLbEndpoints(0).getEndpoint().getAddress()
                .getSocketAddress().getAddress()).isEqualTo("10.66.0.3");
    }

    private RouteConfiguration routes(List<ActiveTenant> tenants) {
        return renderer.render(tenants, false).routes().getFirst();
    }

    private static HttpConnectionManager manager(EnvoyResources resources) throws Exception {
        return resources.listeners().getFirst().getFilterChains(0).getFilters(0).getTypedConfig()
                .unpack(HttpConnectionManager.class);
    }

    private static List<String> strings(Value value) {
        return value.getListValue().getValuesList().stream().map(Value::getStringValue).toList();
    }
}
