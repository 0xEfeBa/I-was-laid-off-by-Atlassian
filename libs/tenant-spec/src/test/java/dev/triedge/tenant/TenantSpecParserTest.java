package dev.triedge.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class TenantSpecParserTest {

    private static final Path EXAMPLES = Path.of(System.getProperty("triedge.examples"));

    static Stream<Path> validExamples() throws IOException {
        return Files.list(EXAMPLES.resolve("valid")).sorted();
    }

    static Stream<Path> invalidExamples() throws IOException {
        return Files.list(EXAMPLES.resolve("invalid")).sorted();
    }

    @ParameterizedTest
    @MethodSource("validExamples")
    void acceptsValidExamples(Path example) throws IOException {
        assertThat(TenantSpecParser.parse(Files.readString(example)))
                .as(example.getFileName().toString())
                .isInstanceOf(TenantSpecParser.Result.Valid.class);
    }

    @ParameterizedTest
    @MethodSource("invalidExamples")
    void rejectsInvalidExamples(Path example) throws IOException {
        assertThat(TenantSpecParser.parse(Files.readString(example)))
                .as(example.getFileName().toString())
                .isInstanceOfSatisfying(TenantSpecParser.Result.Invalid.class,
                        invalid -> assertThat(invalid.errors()).isNotEmpty());
    }

    @Test
    void appliesDefaults() {
        TenantSpec spec = valid("""
                apiVersion: tri-edge/v1
                name: echo
                hosts: [echo.localhost]
                backends: [{ name: main, address: "echo:8180" }]
                routes: [{ path: /, backend: main }]
                """).spec();

        assertThat(spec.regions()).containsExactly("eu", "us", "ap");
        assertThat(spec.routes().getFirst()).isEqualTo(new TenantSpec.Route("/", TenantSpec.Match.PREFIX, List.of(),
                "main", TenantSpec.Auth.NONE, List.of(), TenantSpec.RouteBy.LOCAL, null, null));
        assertThat(spec.backend("main")).isEqualTo(new TenantSpec.Backend("main", "echo", 8180));
        assertThat(spec.backend("other")).isNull();
        assertThat(spec.hook()).isNull();
    }

    @Test
    void readsEveryFieldOfTheFullExample() throws IOException {
        TenantSpec spec = valid(Files.readString(EXAMPLES.resolve("valid/full.yaml"))).spec();

        assertThat(spec.regions()).containsExactly("eu", "us");
        assertThat(spec.hook()).isEqualTo(new TenantSpec.Hook("authz.t-shop.svc", 9002, false));
        assertThat(spec.routes().getFirst().rateLimit())
                .isEqualTo(new TenantSpec.RateLimit(100, TenantSpec.RateLimit.Unit.MINUTE));
        assertThat(spec.routes().getFirst().auth().sidecarName()).isEqualTo("jwt_or_pat");
        assertThat(spec.routes().get(2).match()).isEqualTo(TenantSpec.Match.EXACT);
    }

    @Test
    void canonicalFormIsStableAcrossFormatting() {
        TenantSpecParser.Result.Valid yaml = valid("""
                apiVersion: tri-edge/v1
                name: echo
                hosts: [echo.localhost]
                backends: [{ name: main, address: "echo:8180" }]
                routes: [{ path: /, backend: main }]
                """);
        TenantSpecParser.Result.Valid json = valid("""
                {"routes":[{"backend":"main","path":"/","auth":"none"}],"name":"echo","apiVersion":"tri-edge/v1",
                 "backends":[{"address":"echo:8180","name":"main"}],"hosts":["echo.localhost"],"regions":["eu","us","ap"]}
                """);

        assertThat(json.canonicalJson()).isEqualTo(yaml.canonicalJson());
        assertThat(json.hash()).isEqualTo(yaml.hash()).hasSize(64);
        assertThat(TenantSpecParser.parse(yaml.canonicalJson())).isEqualTo(yaml);
    }

    @Test
    void rejectsGarbageAndOversizedDocuments() {
        assertThat(TenantSpecParser.parse("[1, 2")).isInstanceOf(TenantSpecParser.Result.Invalid.class);
        assertThat(TenantSpecParser.parse("- just a list")).isInstanceOf(TenantSpecParser.Result.Invalid.class);
        assertThat(TenantSpecParser.parse("x".repeat(TenantSpecParser.MAX_DOCUMENT_BYTES + 1)))
                .isInstanceOf(TenantSpecParser.Result.Invalid.class);
    }

    @Test
    void reportsWhereTheProblemIs() {
        assertThat(TenantSpecParser.parse("""
                apiVersion: tri-edge/v1
                name: echo
                hosts: [echo.localhost]
                backends: [{ name: main, address: "echo:99999" }]
                routes: [{ path: /, backend: main }, { path: /, backend: main }]
                hook: { address: "hook:0" }
                """)).isInstanceOfSatisfying(TenantSpecParser.Result.Invalid.class, invalid -> assertThat(invalid.errors())
                .anyMatch(e -> e.contains("port of 'main'"))
                .anyMatch(e -> e.contains("duplicate route"))
                .anyMatch(e -> e.startsWith("/hook")));
    }

    private static TenantSpecParser.Result.Valid valid(String document) {
        TenantSpecParser.Result result = TenantSpecParser.parse(document);
        assertThat(result).isInstanceOf(TenantSpecParser.Result.Valid.class);
        return (TenantSpecParser.Result.Valid) result;
    }
}
