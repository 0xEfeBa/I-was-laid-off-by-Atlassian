package dev.triedge.broker.service;

import dev.triedge.broker.data.ServiceInstance;
import dev.triedge.broker.data.Tenant;
import dev.triedge.broker.data.TenantRepository;
import dev.triedge.common.db.ClusterContext;
import dev.triedge.common.db.ClusterDataSources;
import dev.triedge.common.db.ClusterProperties;
import dev.triedge.common.queue.Job;
import dev.triedge.common.queue.JobQueue;
import dev.triedge.common.region.LeaderResolver;
import dev.triedge.tenant.TenantSpecParser;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public class TenantWorker {

    static final int BATCH = 20;
    static final String UNAVAILABLE = "The control plane could not validate this version";

    private static final Logger log = LoggerFactory.getLogger(TenantWorker.class);

    private final JobQueue jobs;
    private final TenantRepository tenants;
    private final TenantReservations reservations;
    private final TenantEvents events;
    private final ControlPlaneClient controlPlane;
    private final TransactionTemplate transactions;
    private final JsonMapper json;
    private final ClusterDataSources clusters;
    private final LeaderResolver leaders;
    private final String region;

    public TenantWorker(JobQueue jobs, TenantRepository tenants, TenantReservations reservations, TenantEvents events,
            ControlPlaneClient controlPlane, TransactionTemplate transactions, JsonMapper json,
            ClusterDataSources clusters, LeaderResolver leaders, ClusterProperties clusterProperties) {
        this.jobs = jobs;
        this.tenants = tenants;
        this.reservations = reservations;
        this.events = events;
        this.controlPlane = controlPlane;
        this.transactions = transactions;
        this.json = json;
        this.clusters = clusters;
        this.leaders = leaders;
        this.region = clusterProperties.region();
    }

    @Scheduled(fixedDelayString = "${triedge.broker.worker-interval:PT0.2S}")
    public int drain() {
        int processed = 0;
        for (String cluster : clusters.clusters()) {
            if (leaders.leads(cluster, region)) {
                processed += ClusterContext.call(cluster, this::drainCluster);
            }
        }
        return processed;
    }

    private int drainCluster() {
        int processed = 0;
        while (processed < BATCH) {
            Optional<Job> validation = jobs.claim(TenantService.VALIDATE_QUEUE);
            validation.ifPresent(this::validate);
            Optional<Job> deprovision = jobs.claim(TenantService.DEPROVISION_QUEUE);
            deprovision.ifPresent(this::deprovision);
            if (validation.isEmpty() && deprovision.isEmpty()) {
                break;
            }
            processed += (validation.isPresent() ? 1 : 0) + (deprovision.isPresent() ? 1 : 0);
        }
        return processed;
    }

    private void validate(Job job) {
        JsonNode payload = json.readTree(job.payload());
        UUID tenantId = UUID.fromString(payload.get("tenantId").asString());
        int version = payload.get("version").asInt();
        Optional<String> spec = tenants.spec(tenantId, version);
        if (spec.isEmpty()) {
            jobs.complete(job);
            return;
        }
        ControlPlaneClient.Outcome outcome;
        try {
            outcome = controlPlane.validate(json.readTree(spec.get()));
        } catch (RuntimeException e) {
            if (job.attempts() < job.maxAttempts()) {
                log.warn("Validation of tenant {} version {} failed, retrying: {}", tenantId, version, e.getMessage());
                jobs.fail(job, String.valueOf(e.getMessage()));
                return;
            }
            outcome = new ControlPlaneClient.Outcome(false, List.of(UNAVAILABLE));
        }
        if (outcome.valid()) {
            activate(job, tenantId, version);
        } else {
            reject(job, tenantId, version, outcome.errors());
        }
    }

    private void activate(Job job, UUID tenantId, int version) {
        Optional<Tenant> tenant = transactions.execute(status -> {
            Optional<Tenant> live = tenants.lockLive(tenantId);
            live.ifPresent(t -> {
                tenants.activate(tenantId, version);
                tenants.finishOperation(tenantId, ServiceInstance.SUCCEEDED, "Version " + version + " is active");
                events.versionActivated(tenantId, t.name(), version);
            });
            completeOrRollBack(job);
            return live;
        });
        if (tenant != null && tenant.isPresent()) {
            log.info("Tenant {} version {} activated", tenant.get().name(), version);
            releaseUnusedHosts(tenantId, version);
        }
    }

    private void reject(Job job, UUID tenantId, int version, List<String> errors) {
        Optional<Tenant> tenant = transactions.execute(status -> {
            Optional<Tenant> live = tenants.lockLive(tenantId);
            live.ifPresent(t -> {
                tenants.setStatus(tenantId, version, "REJECTED", String.join("\n", errors));
                tenants.finishOperation(tenantId, ServiceInstance.FAILED,
                        "Version " + version + " was rejected: " + String.join("; ", errors));
            });
            completeOrRollBack(job);
            return live;
        });
        if (tenant != null && tenant.isPresent()) {
            log.info("Tenant {} version {} rejected", tenant.get().name(), version);
            Integer active = tenant.get().activeVersion();
            if (active == null) {
                releaseHosts(tenantId, List.of());
            } else {
                releaseUnusedHosts(tenantId, active);
            }
        }
    }

    private void deprovision(Job job) {
        UUID tenantId = UUID.fromString(json.readTree(job.payload()).get("tenantId").asString());
        Optional<Tenant> tenant = transactions.execute(status -> {
            Optional<Tenant> live = tenants.lockLive(tenantId);
            live.ifPresent(t -> {
                tenants.markDeleted(tenantId);
                tenants.deleteInstance(tenantId);
                events.deleted(tenantId, t.name());
            });
            completeOrRollBack(job);
            return live;
        });
        if (tenant != null && tenant.isPresent()) {
            log.info("Tenant {} deleted", tenant.get().name());
            try {
                reservations.releaseName(tenantId, tenant.get().name());
            } catch (RuntimeException e) {
                log.warn("Could not release the name of tenant {}: {}", tenant.get().name(), e.getMessage());
            }
            releaseHosts(tenantId, List.of());
        }
    }

    private void completeOrRollBack(Job job) {
        if (!jobs.complete(job)) {
            throw new IllegalStateException("Lost the lock on job " + job.id());
        }
    }

    private void releaseUnusedHosts(UUID tenantId, int version) {
        String spec = tenants.spec(tenantId, version).orElseThrow();
        if (TenantSpecParser.parse(spec) instanceof TenantSpecParser.Result.Valid valid) {
            releaseHosts(tenantId, valid.spec().hosts());
        }
    }

    private void releaseHosts(UUID tenantId, List<String> keep) {
        try {
            reservations.releaseHosts(tenantId, keep);
        } catch (RuntimeException e) {
            log.warn("Could not release hosts of tenant {}: {}", tenantId, e.getMessage());
        }
    }
}
