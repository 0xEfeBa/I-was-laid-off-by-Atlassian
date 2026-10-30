package dev.triedge.sidecar.authz;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.ListValue;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import java.util.List;
import org.junit.jupiter.api.Test;

class RouteRuleTest {

    @Test
    void readsEveryField() {
        Struct metadata = Struct.newBuilder()
                .putFields("tenant", string("git"))
                .putFields("auth", string("jwt_or_pat"))
                .putFields("scopes", list("git:write"))
                .putFields("hook", string("repo-api:9002"))
                .putFields("hook_fail_open", Value.newBuilder().setBoolValue(true).build())
                .putFields("regions", list("eu", "us"))
                .putFields("backends", list("shard-0", "api"))
                .putFields("route_by", string("home"))
                .build();

        RouteRule rule = RouteRule.from(metadata);

        assertThat(rule).isEqualTo(new RouteRule("git", RouteRule.Auth.JWT_OR_PAT, List.of("git:write"), "repo-api:9002",
                true, List.of("eu", "us"), List.of("shard-0", "api"), RouteRule.RouteBy.HOME));
        assertThat(rule.acceptsAccessTokens()).isTrue();
    }

    @Test
    void missingMetadataDeniesEverything() {
        assertThat(RouteRule.from(Struct.getDefaultInstance()).auth()).isEqualTo(RouteRule.Auth.DENY);
    }

    @Test
    void unknownValuesFallBackToSafeDefaults() {
        RouteRule rule = RouteRule.from(Struct.newBuilder()
                .putFields("auth", string("anything"))
                .putFields("route_by", string("elsewhere"))
                .putFields("hook", string("  "))
                .putFields("scopes", string("not-a-list"))
                .build());

        assertThat(rule.auth()).isEqualTo(RouteRule.Auth.DENY);
        assertThat(rule.routeBy()).isEqualTo(RouteRule.RouteBy.LOCAL);
        assertThat(rule.hook()).isNull();
        assertThat(rule.scopes()).isEmpty();
        assertThat(rule.acceptsAccessTokens()).isFalse();
    }

    private static Value string(String value) {
        return Value.newBuilder().setStringValue(value).build();
    }

    private static Value list(String... values) {
        ListValue.Builder list = ListValue.newBuilder();
        for (String value : values) {
            list.addValues(string(value));
        }
        return Value.newBuilder().setListValue(list).build();
    }
}
