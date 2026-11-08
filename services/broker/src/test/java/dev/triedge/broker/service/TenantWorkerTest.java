package dev.triedge.broker.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.triedge.broker.data.TenantRepository;
import dev.triedge.common.db.ClusterConnection;
import dev.triedge.common.db.ClusterContext;
import dev.triedge.common.db.ClusterDataSources;
import dev.triedge.common.db.ClusterProperties;
import dev.triedge.common.queue.JobQueue;
import dev.triedge.common.region.LeaderResolver;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

class TenantWorkerTest {

    private final JobQueue jobs = mock(JobQueue.class);
    private final ClusterDataSources clusters = mock(ClusterDataSources.class);
    private final LeaderResolver leaders = mock(LeaderResolver.class);
    private final TenantWorker worker = new TenantWorker(jobs, mock(TenantRepository.class),
            mock(TenantReservations.class), mock(TenantEvents.class), mock(ControlPlaneClient.class),
            mock(TransactionTemplate.class), JsonMapper.builder().build(), clusters, leaders,
            new ClusterProperties("eu",
                    Map.of("eu", new ClusterConnection("jdbc:postgresql://localhost/triedge", "broker", "", 1))));

    @Test
    void aFailingClusterDoesNotStopTheOthers() {
        List<String> drained = new ArrayList<>();
        when(clusters.clusters()).thenReturn(new LinkedHashSet<>(List.of("eu", "us", "ap")));
        when(leaders.leads("eu", "eu")).thenReturn(true);
        when(leaders.leads("us", "eu")).thenThrow(new IllegalStateException("etcd is down"));
        when(leaders.leads("ap", "eu")).thenReturn(true);
        when(jobs.claim(anyString())).thenAnswer(call -> {
            String cluster = ClusterContext.current().orElseThrow();
            if (cluster.equals("eu")) {
                throw new IllegalStateException("eu database is down");
            }
            drained.add(cluster);
            return Optional.empty();
        });

        assertThat(worker.drain()).isZero();

        assertThat(drained).contains("ap").doesNotContain("eu", "us");
    }
}
