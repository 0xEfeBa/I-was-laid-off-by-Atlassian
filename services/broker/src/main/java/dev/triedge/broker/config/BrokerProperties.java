package dev.triedge.broker.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("triedge.broker")
public record BrokerProperties(
        String etcdUrl,
        String controlPlaneUrl,
        @DefaultValue("5") int maxTenantsPerUser,
        @DefaultValue("platform") List<String> reservedNames,
        @DefaultValue("PT30S") Duration jobLock,
        @DefaultValue("PT5S") Duration controlPlaneTimeout,
        @DefaultValue("/triedge/db") String leaderNamespace) {
}
