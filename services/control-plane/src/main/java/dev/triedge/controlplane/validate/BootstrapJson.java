package dev.triedge.controlplane.validate;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;
import dev.triedge.controlplane.render.EnvoyResources;
import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;
import io.envoyproxy.envoy.extensions.filters.http.ext_authz.v3.ExtAuthz;
import io.envoyproxy.envoy.extensions.filters.http.ext_authz.v3.ExtAuthzPerRoute;
import io.envoyproxy.envoy.extensions.filters.http.local_ratelimit.v3.LocalRateLimit;
import io.envoyproxy.envoy.extensions.filters.http.router.v3.Router;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager;
import io.envoyproxy.envoy.extensions.upstreams.http.v3.HttpProtocolOptions;

public final class BootstrapJson {

    private static final JsonFormat.Printer PRINTER = JsonFormat.printer()
            .usingTypeRegistry(JsonFormat.TypeRegistry.newBuilder()
                    .add(HttpConnectionManager.getDescriptor())
                    .add(ExtAuthz.getDescriptor())
                    .add(ExtAuthzPerRoute.getDescriptor())
                    .add(LocalRateLimit.getDescriptor())
                    .add(Router.getDescriptor())
                    .add(HttpProtocolOptions.getDescriptor())
                    .build())
            .omittingInsignificantWhitespace();

    private BootstrapJson() {
    }

    public static String of(EnvoyResources resources) {
        Bootstrap bootstrap = Bootstrap.newBuilder()
                .setStaticResources(Bootstrap.StaticResources.newBuilder()
                        .addAllListeners(resources.listeners())
                        .addAllClusters(resources.clusters()))
                .build();
        try {
            return PRINTER.print(bootstrap);
        } catch (InvalidProtocolBufferException e) {
            throw new IllegalStateException("Could not print the Envoy configuration", e);
        }
    }
}
