package dev.triedge.sidecar.hooks;

import io.envoyproxy.envoy.config.core.v3.HeaderValueOption;
import io.envoyproxy.envoy.service.auth.v3.AuthorizationGrpc;
import io.envoyproxy.envoy.service.auth.v3.CheckRequest;
import io.envoyproxy.envoy.service.auth.v3.CheckResponse;
import io.envoyproxy.envoy.type.v3.StatusCode;
import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

public class HookClient implements AutoCloseable {

    public sealed interface Result {

        record Allowed(Map<String, String> headers) implements Result {
        }

        record Denied(int status) implements Result {
        }

        record Unavailable(String reason) implements Result {
        }
    }

    private final Function<String, ManagedChannel> channelFactory;
    private final Duration timeout;
    private final Map<String, ManagedChannel> channels = new ConcurrentHashMap<>();

    public HookClient(Function<String, ManagedChannel> channelFactory, Duration timeout) {
        this.channelFactory = channelFactory;
        this.timeout = timeout;
    }

    public Result call(String target, CheckRequest request) {
        try {
            CheckResponse response = AuthorizationGrpc.newBlockingStub(channels.computeIfAbsent(target, channelFactory))
                    .withDeadlineAfter(timeout.toMillis(), TimeUnit.MILLISECONDS)
                    .check(request);
            if (response.getStatus().getCode() == 0) {
                Map<String, String> headers = new LinkedHashMap<>();
                for (HeaderValueOption option : response.getOkResponse().getHeadersList()) {
                    headers.put(option.getHeader().getKey(), option.getHeader().getValue());
                }
                return new Result.Allowed(headers);
            }
            return new Result.Denied(supported(response.getDeniedResponse().getStatus().getCodeValue()));
        } catch (StatusRuntimeException e) {
            return new Result.Unavailable(e.getStatus().getCode().name());
        }
    }

    public static int supported(int status) {
        if (status >= 400 && status < 600 && StatusCode.forNumber(status) != null) {
            return status;
        }
        return status >= 500 && status < 600 ? 502 : 403;
    }

    @Override
    public void close() {
        channels.values().forEach(ManagedChannel::shutdownNow);
    }
}
