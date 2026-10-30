package dev.triedge.sidecar.grpc;

import com.google.protobuf.Struct;
import com.google.rpc.Code;
import com.google.rpc.Status;
import dev.triedge.sidecar.auth.AuthenticatedUser;
import dev.triedge.sidecar.authz.AuthPolicy;
import dev.triedge.sidecar.authz.RouteRule;
import dev.triedge.sidecar.hooks.HeaderAllowlist;
import dev.triedge.sidecar.hooks.HookClient;
import dev.triedge.sidecar.routing.PlatformRouting;
import io.envoyproxy.envoy.config.core.v3.HeaderValue;
import io.envoyproxy.envoy.config.core.v3.HeaderValueOption;
import io.envoyproxy.envoy.service.auth.v3.AuthorizationGrpc;
import io.envoyproxy.envoy.service.auth.v3.CheckRequest;
import io.envoyproxy.envoy.service.auth.v3.CheckResponse;
import io.envoyproxy.envoy.service.auth.v3.DeniedHttpResponse;
import io.envoyproxy.envoy.service.auth.v3.OkHttpResponse;
import io.envoyproxy.envoy.type.v3.HttpStatus;
import io.envoyproxy.envoy.type.v3.StatusCode;
import io.grpc.stub.StreamObserver;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AuthorizationService extends AuthorizationGrpc.AuthorizationImplBase {

    static final List<String> RESERVED_PREFIXES =
            List.of("x-user-", "x-tenant-", "x-target-region", "x-backend-set", "x-internal-");

    private static final Logger log = LoggerFactory.getLogger(AuthorizationService.class);

    private final AuthPolicy policy;
    private final PlatformRouting routing;
    private final HookClient hooks;
    private final HeaderAllowlist allowlist;

    public AuthorizationService(AuthPolicy policy, PlatformRouting routing, HookClient hooks,
            HeaderAllowlist allowlist) {
        this.policy = policy;
        this.routing = routing;
        this.hooks = hooks;
        this.allowlist = allowlist;
    }

    @Override
    public void check(CheckRequest request, StreamObserver<CheckResponse> responses) {
        CheckResponse response;
        try {
            response = decide(request);
        } catch (RuntimeException e) {
            log.error("Authorization check failed", e);
            response = denied(503, "Authorization is temporarily unavailable", false);
        }
        responses.onNext(response);
        responses.onCompleted();
    }

    CheckResponse decide(CheckRequest request) {
        Map<String, String> requestHeaders = request.getAttributes().getRequest().getHttp().getHeadersMap();
        RouteRule rule = RouteRule.from(request.getAttributes().getRouteMetadataContext()
                .getFilterMetadataOrDefault(RouteRule.METADATA_NAMESPACE, Struct.getDefaultInstance()));

        AuthPolicy.Decision decision = policy.decide(rule, requestHeaders.get("authorization"));
        if (decision instanceof AuthPolicy.Decision.Deny deny) {
            return denied(deny.status(), deny.detail(), deny.challengeBasic());
        }
        AuthenticatedUser user = ((AuthPolicy.Decision.Allow) decision).user();

        Map<String, String> added = new LinkedHashMap<>();
        if (user != null) {
            added.put("x-user-id", user.id().toString());
            added.put("x-user-name", user.username());
            added.put("x-user-role", user.role());
            added.put("x-user-home-region", user.homeRegion());
            added.put("x-user-scopes", String.join(",", user.scopes()));
            added.put("x-user-auth", user.kind() == AuthenticatedUser.Kind.SESSION ? "session" : "token");
        }
        added.put(HeaderAllowlist.TARGET_REGION, routing.targetRegion(rule, user));

        if (rule.hook() != null) {
            HookClient.Result result = hooks.call(rule.hook(), withVerifiedHeaders(request, added));
            switch (result) {
                case HookClient.Result.Allowed allowed -> added.putAll(allowlist.filter(rule, allowed.headers()));
                case HookClient.Result.Denied hookDenied -> {
                    return denied(hookDenied.status(), "Access denied by the service", false);
                }
                case HookClient.Result.Unavailable unavailable -> {
                    log.warn("Authorization hook {} for tenant {} failed: {}", rule.hook(), rule.tenant(),
                            unavailable.reason());
                    if (!rule.hookFailOpen()) {
                        return denied(503, "The service's authorization check is unavailable", false);
                    }
                }
            }
        }

        OkHttpResponse.Builder ok = OkHttpResponse.newBuilder();
        added.forEach((name, value) -> ok.addHeaders(header(name, value)));
        requestHeaders.keySet().stream()
                .filter(name -> isReserved(name) && !added.containsKey(name.toLowerCase(Locale.ROOT)))
                .forEach(ok::addHeadersToRemove);
        return CheckResponse.newBuilder()
                .setStatus(Status.newBuilder().setCode(Code.OK_VALUE))
                .setOkResponse(ok)
                .build();
    }

    private static CheckRequest withVerifiedHeaders(CheckRequest request, Map<String, String> verified) {
        Map<String, String> headers = new LinkedHashMap<>();
        request.getAttributes().getRequest().getHttp().getHeadersMap().forEach((name, value) -> {
            if (!isReserved(name)) {
                headers.put(name, value);
            }
        });
        headers.putAll(verified);
        CheckRequest.Builder builder = request.toBuilder();
        builder.getAttributesBuilder().getRequestBuilder().getHttpBuilder().clearHeaders().putAllHeaders(headers);
        return builder.build();
    }

    static boolean isReserved(String header) {
        String name = header.toLowerCase(Locale.ROOT);
        return RESERVED_PREFIXES.stream().anyMatch(name::startsWith);
    }

    private static HeaderValueOption header(String name, String value) {
        return HeaderValueOption.newBuilder()
                .setHeader(HeaderValue.newBuilder().setKey(name).setValue(value))
                .setAppendAction(HeaderValueOption.HeaderAppendAction.OVERWRITE_IF_EXISTS_OR_ADD)
                .build();
    }

    static CheckResponse denied(int status, String detail, boolean challengeBasic) {
        String body = "{\"type\":\"about:blank\",\"status\":" + status + ",\"detail\":\""
                + detail.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}";
        DeniedHttpResponse.Builder denied = DeniedHttpResponse.newBuilder()
                .setStatus(HttpStatus.newBuilder().setCode(StatusCode.forNumber(status)))
                .setBody(body)
                .addHeaders(header("content-type", "application/problem+json"));
        if (challengeBasic) {
            denied.addHeaders(header("www-authenticate", "Basic realm=\"tri-edge\""));
        }
        return CheckResponse.newBuilder()
                .setStatus(Status.newBuilder().setCode(status == 401 ? Code.UNAUTHENTICATED_VALUE : Code.PERMISSION_DENIED_VALUE))
                .setDeniedResponse(denied)
                .build();
    }
}
