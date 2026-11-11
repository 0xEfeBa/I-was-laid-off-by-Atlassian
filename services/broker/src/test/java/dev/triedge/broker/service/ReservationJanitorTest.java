package dev.triedge.broker.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.triedge.broker.data.TenantRepository;
import dev.triedge.common.db.ClusterConnection;
import dev.triedge.common.db.ClusterProperties;
import dev.triedge.common.etcd.EtcdClient;
import dev.triedge.common.region.LeaderResolver;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ReservationJanitorTest {

    private final TenantReservations reservations = mock(TenantReservations.class);
    private final TenantRepository tenants = mock(TenantRepository.class);
    private final EtcdClient etcd = mock(EtcdClient.class);
    private final LeaderResolver leaders = mock(LeaderResolver.class);
    private final ReservationJanitor janitor = new ReservationJanitor(reservations, tenants, etcd,
            JsonMapper.builder().build(), Clock.systemUTC(), leaders, new ClusterProperties("eu",
                    Map.of("eu", new ClusterConnection("jdbc:postgresql://localhost/triedge", "broker", "", 1))));

    @Test
    void anUnreachableRegionDoesNotStopTheSweep() {
        TenantReservations.Holder far = new TenantReservations.Holder(UUID.randomUUID(), "ap", Instant.EPOCH);
        TenantReservations.Holder near = new TenantReservations.Holder(UUID.randomUUID(), "eu", Instant.EPOCH);
        Map<String, TenantReservations.Holder> names = new LinkedHashMap<>();
        names.put("far", far);
        names.put("near", near);
        when(reservations.names()).thenReturn(names);
        when(reservations.hosts()).thenReturn(Map.of());
        when(leaders.leads("ap", "eu")).thenThrow(new IllegalStateException("ap is unreachable"));
        when(leaders.leads("eu", "eu")).thenReturn(true);
        when(tenants.holding(any())).thenReturn(Optional.empty());
        when(etcd.get(TenantReservations.NAMES + "near")).thenReturn(Optional.of(near.encode()));
        when(etcd.deleteIfValue(TenantReservations.NAMES + "near", near.encode())).thenReturn(true);

        assertThat(janitor.releaseAbandoned()).isEqualTo(1);

        verify(etcd, never()).get(TenantReservations.NAMES + "far");
        verify(etcd).deleteIfValue(TenantReservations.NAMES + "near", near.encode());
    }
}
