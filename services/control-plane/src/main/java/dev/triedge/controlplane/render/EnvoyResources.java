package dev.triedge.controlplane.render;

import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.listener.v3.Listener;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;
import java.util.List;

public record EnvoyResources(List<Listener> listeners, List<RouteConfiguration> routes, List<Cluster> clusters) {
}
