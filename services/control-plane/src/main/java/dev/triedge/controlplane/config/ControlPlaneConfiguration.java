package dev.triedge.controlplane.config;

import dev.triedge.common.db.ClusterDataSources;
import dev.triedge.common.db.ClusterProperties;
import dev.triedge.common.etcd.EtcdClient;
import dev.triedge.controlplane.render.ConfigRenderer;
import dev.triedge.controlplane.report.AppliedReporter;
import dev.triedge.controlplane.source.SystemTenants;
import dev.triedge.controlplane.source.TenantDirectory;
import dev.triedge.controlplane.validate.EnvoyValidator;
import dev.triedge.controlplane.validate.NamespaceOwnershipCheck;
import dev.triedge.controlplane.validate.ProcessEnvoyValidator;
import dev.triedge.controlplane.validate.ValidationService;
import dev.triedge.controlplane.xds.AckTracker;
import dev.triedge.controlplane.xds.SnapshotPublisher;
import dev.triedge.controlplane.xds.XdsServer;
import io.envoyproxy.controlplane.cache.v3.SimpleCache;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({ControlPlaneProperties.class, ClusterProperties.class})
@EnableScheduling
public class ControlPlaneConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ControlPlaneConfiguration.class);

    @Bean(destroyMethod = "close")
    ClusterDataSources clusterDataSources(ClusterProperties properties) {
        return new ClusterDataSources(properties.clusters());
    }

    @Bean
    TenantDirectory tenantDirectory(ClusterDataSources clusters, ControlPlaneProperties properties, JsonMapper json) {
        return new TenantDirectory(clusters, SystemTenants.load(properties.systemTenantsDir()), json, Duration.ofSeconds(2));
    }

    @Bean
    ConfigRenderer configRenderer(ClusterProperties clusters, ControlPlaneProperties properties) {
        return new ConfigRenderer(clusters.region(), properties.listenerPort(), properties.sidecarHost(),
                properties.sidecarPort(), properties.remoteRegions());
    }

    @Bean
    SimpleCache<String> snapshotCache() {
        return new SimpleCache<>(node -> SnapshotPublisher.GROUP);
    }

    @Bean(destroyMethod = "close")
    SnapshotPublisher snapshotPublisher(SimpleCache<String> cache, ConfigRenderer renderer, TenantDirectory directory,
            ControlPlaneProperties properties) {
        SnapshotPublisher publisher = new SnapshotPublisher(cache, renderer, directory::all, properties.coalesce());
        directory.onChange(publisher::requestPublish);
        return publisher;
    }

    @Bean
    AckTracker ackTracker(SnapshotPublisher publisher) {
        return new AckTracker(publisher::version);
    }

    @Bean
    XdsServer xdsServer(ControlPlaneProperties properties, SimpleCache<String> cache, AckTracker tracker,
            SnapshotPublisher publisher) {
        return new XdsServer(properties.xdsPort(), cache, tracker, publisher);
    }

    @Bean
    AppliedReporter appliedReporter(SnapshotPublisher publisher, AckTracker tracker, ControlPlaneProperties properties,
            ClusterProperties clusters) {
        return new AppliedReporter(publisher, tracker, new EtcdClient(RestClient.create(properties.etcdUrl())),
                clusters.region());
    }

    @Bean
    EnvoyValidator envoyValidator(ControlPlaneProperties properties) {
        if (properties.envoyValidation()) {
            return new ProcessEnvoyValidator(properties.envoyBinary());
        }
        log.warn("Envoy validation is disabled; configurations are only checked by the schema");
        return resources -> List.of();
    }

    @Bean
    ValidationService validationService(TenantDirectory directory, ControlPlaneProperties properties,
            ConfigRenderer renderer, EnvoyValidator envoy) {
        return new ValidationService(directory, new NamespaceOwnershipCheck(properties.forbiddenBackendHosts()),
                renderer, envoy);
    }
}
