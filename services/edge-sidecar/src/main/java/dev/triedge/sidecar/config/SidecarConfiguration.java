package dev.triedge.sidecar.config;

import dev.triedge.common.db.ClusterDataSources;
import dev.triedge.common.db.ClusterProperties;
import dev.triedge.common.etcd.EtcdClient;
import dev.triedge.common.http.RestClients;
import dev.triedge.common.outbox.OutboxMetrics;
import dev.triedge.common.region.LeaderResolver;
import dev.triedge.sidecar.auth.JwksProvider;
import dev.triedge.sidecar.auth.JwtValidator;
import dev.triedge.sidecar.auth.PatValidator;
import dev.triedge.sidecar.authz.AuthPolicy;
import dev.triedge.sidecar.directory.IdentityDirectory;
import dev.triedge.sidecar.directory.IdentityEventApplier;
import dev.triedge.sidecar.directory.IdentityIndex;
import dev.triedge.sidecar.directory.InitialLoader;
import dev.triedge.sidecar.grpc.AuthorizationService;
import dev.triedge.sidecar.grpc.GrpcServer;
import dev.triedge.sidecar.hooks.HeaderAllowlist;
import dev.triedge.sidecar.hooks.HookChannels;
import dev.triedge.sidecar.hooks.HookClient;
import dev.triedge.sidecar.routing.LeaderCache;
import dev.triedge.sidecar.routing.PlatformRouting;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({SidecarProperties.class, ClusterProperties.class})
@EnableScheduling
public class SidecarConfiguration {

    @Bean(destroyMethod = "close")
    ClusterDataSources clusterDataSources(ClusterProperties properties) {
        return new ClusterDataSources(properties.clusters());
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    IdentityDirectory identityDirectory(ClusterDataSources clusters, IdentityIndex index, JsonMapper json, Clock clock,
            MeterRegistry registry, SidecarProperties properties) {
        return new IdentityDirectory(clusters, new InitialLoader(index), new IdentityEventApplier(index, json),
                Duration.ofSeconds(2), properties.pollInterval(), clock, new OutboxMetrics(registry, "identity", clock));
    }

    @Bean
    JwksProvider jwksProvider(SidecarProperties properties, Clock clock, RestClient.Builder http) {
        return new JwksProvider(RestClients.create(http, properties.jwksTimeout()), properties.jwksUrl(),
                properties.jwksMinRefresh(), clock);
    }

    @Bean
    AuthPolicy authPolicy(JwksProvider jwks, SidecarProperties properties, IdentityIndex index, Clock clock) {
        return new AuthPolicy(new JwtValidator(jwks, properties.issuer(), clock), new PatValidator(index, clock), index);
    }

    @Bean(destroyMethod = "close")
    HookClient hookClient(SidecarProperties properties) {
        return new HookClient(target -> HookChannels.create(target, properties.hookSourceAddress()),
                properties.hookTimeout());
    }

    @Bean
    PlatformRouting platformRouting(SidecarProperties properties, ClusterProperties clusters, Clock clock,
            RestClient.Builder http) {
        if (properties.etcdUrl() == null) {
            return new PlatformRouting(clusters.region());
        }
        LeaderResolver leaders = new LeaderResolver(
                new EtcdClient(RestClients.create(http, properties.etcdUrl(), properties.etcdTimeout())),
                properties.leaderNamespace());
        return new PlatformRouting(clusters.region(),
                new LeaderCache(leaders::leaderRegion, properties.leaderCacheTtl(), clock));
    }

    @Bean
    AuthorizationService authorizationService(AuthPolicy policy, HookClient hooks, PlatformRouting routing) {
        return new AuthorizationService(policy, routing, hooks, new HeaderAllowlist());
    }

    @Bean
    GrpcServer grpcServer(SidecarProperties properties, AuthorizationService service) {
        return new GrpcServer(properties.grpcPort(), service, properties.grpcThreads());
    }
}
