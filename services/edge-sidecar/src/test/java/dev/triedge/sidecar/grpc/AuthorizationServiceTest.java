package dev.triedge.sidecar.grpc;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.ListValue;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import com.google.rpc.Code;
import dev.triedge.sidecar.TestTokens;
import dev.triedge.sidecar.auth.JwksProvider;
import dev.triedge.sidecar.auth.JwtValidator;
import dev.triedge.sidecar.auth.PatValidator;
import dev.triedge.sidecar.authz.AuthPolicy;
import dev.triedge.sidecar.authz.RouteRule;
import dev.triedge.sidecar.directory.IdentityIndex;
import dev.triedge.sidecar.hooks.HeaderAllowlist;
import dev.triedge.sidecar.hooks.HookClient;
import dev.triedge.sidecar.routing.PlatformRouting;
import io.envoyproxy.envoy.config.core.v3.HeaderValueOption;
import io.envoyproxy.envoy.config.core.v3.Metadata;
import io.envoyproxy.envoy.service.auth.v3.AttributeContext;
import io.envoyproxy.envoy.service.auth.v3.CheckRequest;
import io.envoyproxy.envoy.service.auth.v3.CheckResponse;
import io.grpc.stub.StreamObserver;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class AuthorizationServiceTest {

    private final TestTokens tokens = new TestTokens();
    private final IdentityIndex index = new IdentityIndex();
    private final AtomicReference<HookClient.Result> hookResult = new AtomicReference<>();
    private final AtomicReference<CheckRequest> hookRequest = new AtomicReference<>();
    private final HookClient hooks = new HookClient(target -> {
        throw new IllegalStateException("not used");
    }, Duration.ofMillis(50)) {
        @Override
        public Result call(String target, CheckRequest request) {
            hookRequest.set(request);
            return hookResult.get();
        }
    };
    private final AuthorizationService service = new AuthorizationService(
            new AuthPolicy(new JwtValidator(new JwksProvider(RestClient.create(), tokens.jwksUrl(), Duration.ofSeconds(30),
                    Clock.systemUTC()), TestTokens.ISSUER, Clock.systemUTC()), new PatValidator(index, Clock.systemUTC()), index),
            new PlatformRouting("eu"),
            hooks,
            new HeaderAllowlist());
    private final UUID userId = UUID.randomUUID();

    @AfterEach
    void close() {
        tokens.close();
    }

    @Test
    void verifiedIdentityIsAddedAndSpoofedHeadersAreRemoved() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("authorization", "Bearer " + tokens.token(userId, "us"));
        headers.put("x-user-id", "00000000-0000-0000-0000-000000000000");
        headers.put("x-user-role", "ADMIN");
        headers.put("x-internal-debug", "1");
        headers.put("x-tenant-repo-id", "42");
        headers.put("x-target-region", "ap");
        headers.put("accept", "application/json");

        CheckResponse response = service.decide(request(rule("jwt", null, false), headers));

        assertThat(response.getStatus().getCode()).isEqualTo(Code.OK_VALUE);
        Map<String, String> added = added(response);
        assertThat(added).containsEntry("x-user-id", userId.toString())
                .containsEntry("x-user-role", "USER")
                .containsEntry("x-user-home-region", "us")
                .containsEntry("x-user-auth", "session")
                .containsEntry("x-target-region", "eu");
        assertThat(response.getOkResponse().getHeadersToRemoveList())
                .containsExactlyInAnyOrder("x-internal-debug", "x-tenant-repo-id")
                .doesNotContain("accept", "authorization");
    }

    @Test
    void anonymousRoutesStillStripSpoofedIdentity() {
        CheckResponse response = service.decide(request(rule("none", null, false),
                Map.of("x-user-id", "00000000-0000-0000-0000-000000000000", "x-user-role", "ADMIN")));

        assertThat(response.getStatus().getCode()).isEqualTo(Code.OK_VALUE);
        assertThat(added(response)).containsOnlyKeys("x-target-region");
        assertThat(response.getOkResponse().getHeadersToRemoveList()).containsExactlyInAnyOrder("x-user-id", "x-user-role");
    }

    @Test
    void deniedRequestsGetAProblemResponse() {
        CheckResponse response = service.decide(request(rule("jwt_or_pat", null, false), Map.of()));

        assertThat(response.getStatus().getCode()).isEqualTo(Code.UNAUTHENTICATED_VALUE);
        assertThat(response.getDeniedResponse().getStatus().getCodeValue()).isEqualTo(401);
        assertThat(response.getDeniedResponse().getBody()).contains("\"status\":401");
        assertThat(headerNames(response.getDeniedResponse().getHeadersList())).contains("www-authenticate", "content-type");
        assertThat(service.decide(request(Struct.getDefaultInstance(), Map.of())).getDeniedResponse().getStatus().getCodeValue())
                .isEqualTo(403);
    }

    @Test
    void hookSeesOnlyVerifiedIdentityAndCanOnlyAddAllowedHeaders() {
        hookResult.set(new HookClient.Result.Allowed(Map.of(
                "x-backend-set", "shard-2",
                "x-target-region", "us",
                "x-user-role", "ADMIN")));

        CheckResponse response = service.decide(request(rule("jwt", "repo-api:9002", false),
                Map.of("authorization", "Bearer " + tokens.token(userId, "eu"), "x-user-role", "ADMIN")));

        Map<String, String> sentToHook = hookRequest.get().getAttributes().getRequest().getHttp().getHeadersMap();
        assertThat(sentToHook).containsEntry("x-user-role", "USER").containsEntry("x-user-id", userId.toString());
        assertThat(added(response)).containsEntry("x-backend-set", "shard-2")
                .containsEntry("x-target-region", "us")
                .containsEntry("x-user-role", "USER");
    }

    @Test
    void hookDecisionsAndFailuresAreRespected() {
        Map<String, String> headers = Map.of("authorization", "Bearer " + tokens.token(userId, "eu"));

        hookResult.set(new HookClient.Result.Denied(404));
        assertThat(service.decide(request(rule("jwt", "hook", false), headers)).getDeniedResponse().getStatus().getCodeValue())
                .isEqualTo(404);

        hookResult.set(new HookClient.Result.Unavailable("DEADLINE_EXCEEDED"));
        assertThat(service.decide(request(rule("jwt", "hook", false), headers)).getDeniedResponse().getStatus().getCodeValue())
                .isEqualTo(503);
        assertThat(service.decide(request(rule("jwt", "hook", true), headers)).getStatus().getCode())
                .isEqualTo(Code.OK_VALUE);
    }

    @Test
    void unexpectedErrorsFailClosed() {
        hookResult.set(null);
        List<CheckResponse> responses = new ArrayList<>();

        service.check(request(rule("jwt", "hook", false), Map.of("authorization", "Bearer " + tokens.token(userId, "eu"))),
                new StreamObserver<>() {
                    @Override
                    public void onNext(CheckResponse value) {
                        responses.add(value);
                    }

                    @Override
                    public void onError(Throwable t) {
                    }

                    @Override
                    public void onCompleted() {
                    }
                });

        assertThat(responses).singleElement()
                .satisfies(r -> assertThat(r.getDeniedResponse().getStatus().getCodeValue()).isEqualTo(503));
    }

    private static Struct rule(String auth, String hook, boolean failOpen) {
        Struct.Builder rule = Struct.newBuilder()
                .putFields("tenant", Value.newBuilder().setStringValue("platform").build())
                .putFields("auth", Value.newBuilder().setStringValue(auth).build())
                .putFields("regions", Value.newBuilder().setListValue(ListValue.newBuilder()
                        .addValues(Value.newBuilder().setStringValue("eu"))
                        .addValues(Value.newBuilder().setStringValue("us"))).build())
                .putFields("backends", Value.newBuilder().setListValue(ListValue.newBuilder()
                        .addValues(Value.newBuilder().setStringValue("shard-2"))).build())
                .putFields("hook_fail_open", Value.newBuilder().setBoolValue(failOpen).build());
        if (hook != null) {
            rule.putFields("hook", Value.newBuilder().setStringValue(hook).build());
        }
        return rule.build();
    }

    private static CheckRequest request(Struct rule, Map<String, String> headers) {
        return CheckRequest.newBuilder()
                .setAttributes(AttributeContext.newBuilder()
                        .setRequest(AttributeContext.Request.newBuilder()
                                .setHttp(AttributeContext.HttpRequest.newBuilder().putAllHeaders(headers)))
                        .setRouteMetadataContext(Metadata.newBuilder().putFilterMetadata(RouteRule.METADATA_NAMESPACE, rule)))
                .build();
    }

    private static Map<String, String> added(CheckResponse response) {
        Map<String, String> added = new LinkedHashMap<>();
        response.getOkResponse().getHeadersList().forEach(h -> added.put(h.getHeader().getKey(), h.getHeader().getValue()));
        return added;
    }

    private static List<String> headerNames(List<HeaderValueOption> headers) {
        return headers.stream().map(h -> h.getHeader().getKey()).toList();
    }
}
