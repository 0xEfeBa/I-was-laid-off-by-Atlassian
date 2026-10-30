package dev.triedge.controlplane.validate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.triedge.controlplane.TestTenants;
import dev.triedge.controlplane.render.ConfigRenderer;
import dev.triedge.controlplane.render.EnvoyResources;
import dev.triedge.controlplane.source.ActiveTenant;
import dev.triedge.controlplane.source.TenantDirectory;
import dev.triedge.tenant.TenantSpec;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ValidationServiceTest {

    private final TenantDirectory directory = mock(TenantDirectory.class);
    private final AtomicReference<EnvoyResources> validated = new AtomicReference<>();
    private final AtomicReference<List<String>> envoyErrors = new AtomicReference<>(List.of());
    private final ValidationService service = new ValidationService(directory,
            new NamespaceOwnershipCheck(List.of("identity", "postgres")),
            new ConfigRenderer("eu", 8080, "edge-sidecar", 9001),
            resources -> {
                validated.set(resources);
                return envoyErrors.get();
            });
    private final ActiveTenant blog = TestTenants.tenant("blog", "blog.test", "blog.t-blog.svc", 80);

    @BeforeEach
    void existingTenants() {
        when(directory.all()).thenReturn(List.of(blog));
        when(directory.get("blog")).thenReturn(Optional.of(blog));
        when(directory.get("platform")).thenReturn(Optional.of(new ActiveTenant(blog.id(), "platform", 0, blog.spec(), true)));
        when(directory.hostOwner("blog.test")).thenReturn(Optional.of("blog"));
    }

    @Test
    void validTenantIsCheckedTogetherWithEveryOtherTenant() {
        ValidationService.Outcome outcome = service.validate(spec("shop", "shop.test", "api.t-shop.svc"));

        assertThat(outcome).isEqualTo(new ValidationService.Outcome(true, List.of()));
        assertThat(validated.get().clusters()).extracting(c -> c.getName())
                .contains("tenant.blog.main", "tenant.shop.main");
        assertThat(validated.get().routes()).isEmpty();
    }

    @Test
    void updatingATenantReplacesItsCurrentVersion() {
        assertThat(service.validate(spec("blog", "blog.test", "new.t-blog.svc")).valid()).isTrue();
        assertThat(validated.get().clusters()).filteredOn(c -> c.getName().equals("tenant.blog.main")).hasSize(1);
    }

    @Test
    void rejectsHostsOfOtherTenantsReservedNamesAndPlatformBackends() {
        assertThat(service.validate(spec("shop", "blog.test", "api.t-shop.svc")).errors())
                .singleElement().asString().contains("already used by another tenant");
        assertThat(service.validate(spec("platform", "x.test", "api.t-shop.svc")).errors())
                .singleElement().asString().contains("reserved");
        assertThat(service.validate(spec("shop", "shop.test", "postgres")).errors())
                .singleElement().asString().contains("may not use");
    }

    @Test
    void envoyErrorsMakeTheTenantInvalid() {
        envoyErrors.set(List.of("cluster: invalid"));

        assertThat(service.validate(spec("shop", "shop.test", "api.t-shop.svc")))
                .isEqualTo(new ValidationService.Outcome(false, List.of("cluster: invalid")));
    }

    private static TenantSpec spec(String name, String host, String backendHost) {
        return TestTenants.tenant(name, host, backendHost, 80).spec();
    }
}
