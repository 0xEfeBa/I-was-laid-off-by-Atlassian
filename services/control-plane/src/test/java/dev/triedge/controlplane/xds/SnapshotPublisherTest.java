package dev.triedge.controlplane.xds;

import static org.assertj.core.api.Assertions.assertThat;

import dev.triedge.controlplane.TestTenants;
import dev.triedge.controlplane.render.ConfigRenderer;
import dev.triedge.controlplane.source.ActiveTenant;
import io.envoyproxy.controlplane.cache.Resources;
import io.envoyproxy.controlplane.cache.v3.SimpleCache;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class SnapshotPublisherTest {

    private final SimpleCache<String> cache = new SimpleCache<>(node -> SnapshotPublisher.GROUP);
    private final List<ActiveTenant> tenants = new CopyOnWriteArrayList<>();
    private final SnapshotPublisher publisher = new SnapshotPublisher(cache, new ConfigRenderer("eu", 8080, "sidecar", 9001),
            () -> tenants, Duration.ofMillis(100));

    @AfterEach
    void close() {
        publisher.close();
    }

    @Test
    void publishesVersionedSnapshotsAndTracksWhatChanged() {
        SnapshotPublisher.Published first = publisher.publishNow();
        assertThat(first.version()).isEqualTo(1);
        assertThat(cache.getSnapshot(SnapshotPublisher.GROUP).clusters().version()).isNotEmpty();

        tenants.add(TestTenants.tenant("blog", "blog.test", "blog.svc", 80));
        SnapshotPublisher.Published second = publisher.publishNow();

        assertThat(second.version()).isEqualTo(2);
        assertThat(second.requiredAcks()).containsEntry(Resources.V3.CLUSTER_TYPE_URL, 2L)
                .containsEntry(Resources.V3.ROUTE_TYPE_URL, 2L)
                .containsEntry(Resources.V3.LISTENER_TYPE_URL, 1L);
        assertThat(second.tenantVersions()).containsEntry("blog", 1);
        assertThat(publisher.latest()).isEqualTo(second);
    }

    @Test
    void burstsOfChangesAreCoalescedIntoOnePublish() throws InterruptedException {
        publisher.publishNow();
        for (int i = 0; i < 20; i++) {
            tenants.add(TestTenants.tenant("t" + i, "t" + i + ".test", "t" + i + ".svc", 80));
            publisher.requestPublish();
        }

        Thread.sleep(400);

        assertThat(publisher.version()).isEqualTo(2);
        assertThat(publisher.latest().tenantVersions()).hasSize(20);
    }

    @Test
    void systemTenantsAreNotReportedAsTenantVersions() {
        tenants.add(new ActiveTenant(UUID.randomUUID(), "platform", 0,
                TestTenants.tenant("platform", "localhost.test", "identity", 8130).spec(), true));

        assertThat(publisher.publishNow().tenantVersions()).isEmpty();
    }
}
