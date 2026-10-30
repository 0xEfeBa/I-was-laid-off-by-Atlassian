package dev.triedge.controlplane.config;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("triedge.control-plane")
public record ControlPlaneProperties(
        @DefaultValue("18000") int xdsPort,
        @DefaultValue("8080") int listenerPort,
        @DefaultValue("edge-sidecar") String sidecarHost,
        @DefaultValue("9001") int sidecarPort,
        @Nullable String systemTenantsDir,
        @DefaultValue("/usr/local/bin/envoy") String envoyBinary,
        @DefaultValue("true") boolean envoyValidation,
        String etcdUrl,
        @DefaultValue({
            "localhost", "identity", "edge-sidecar", "control-plane", "broker", "postgres", "etcd",
            "prometheus", "loki", "tempo", "grafana", "envoy", "metadata.google.internal"
        }) List<String> forbiddenBackendHosts,
        @DefaultValue("PT0.1S") Duration coalesce,
        @DefaultValue Map<String, String> remoteRegions) {
}
