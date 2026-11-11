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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
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
    static final String STOPPED = "The worker stopped before the job finished";

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

    @Scheduled(fixedDelayString = "${triedge.broker.worker-interval:PT0.2S}",
            initialDelayString = "${triedge.broker.worker-interval:PT0.2S}")
    public int drain() {
        int processed = 0;
        for (String cluster : clusters.clusters()) {
            try {
                if (leaders.leads(cluster, region)) {
                    processed += ClusterContext.call(cluster, this::drainCluster);
                }
            } catch (RuntimeException e) {
                log.warn("Could not process the jobs of cluster {}: {}", cluster, e.getMessage());
            }
        }
        return processed;
    }

    private int drainCluster() {
        int processed = 0;
        while (processed < BATCH) {
            int round = abandonExpired(TenantService.VALIDATE_QUEUE)
                    + abandonExpired(TenantService.DEPROVISION_QUEUE)
                    + process(TenantService.VALIDATE_QUEUE, this::validate)
                    + process(TenantService.DEPROVISION_QUEUE, this::deprovision);
            if (round == 0) {
                break;
            }
            processed += round;
        }
        return processed;
    }

    private int process(String queue, Consumer<Job> handler) {
        Optional<Job> claimed = jobs.claim(queue);
        if (claimed.isEmpty()) {
            return 0;
        }
        Job job = claimed.get();
        try {
            handler.accept(job);
        } catch (RuntimeException e) {
            log.warn("Job {} on {} failed on attempt {} of {}: {}", job.id(), queue, job.attempts(),
                    job.maxAttempts(), e.getMessage());
            String error = String.valueOf(e.getMessage());
            Optional<Tenant> rejected = transactions.execute(status ->
                    jobs.fail(job, error) && job.attempts() >= job.maxAttempts()
                            ? abandon(job, error)
                            : Optional.<Tenant>empty());
            Objects.requireNonNullElse(rejected, Optional.<Tenant>empty()).ifPresent(this::releaseAfterRejection);
        }
        return 1;
    }

    private int abandonExpired(String queue) {
        Optional<Optional<Tenant>> expired = transactions.execute(status ->
                jobs.expire(queue).map(job -> abandon(job, STOPPED)));
        if (expired == null || expired.isEmpty()) {
            return 0;
        }
        expired.get().ifPresent(this::releaseAfterRejection);
        return 1;
    }

    private Optional<Tenant> abandon(Job job, String error) {
        Optional<JsonNode> payload = payloadOf(job);
        Optional<UUID> tenantId = payload.map(p -> UUID.fromString(p.get("tenantId").asString()));
        if (tenantId.isEmpty()) {
            log.error("Gave up on job {} on {}, which names no tenant", job.id(), job.queue());
            return Optional.empty();
        }
        if (TenantService.DEPROVISION_QUEUE.equals(job.queue())) {
            tenants.lockLive(tenantId.get()).ifPresent(t -> tenants.finishOperation(tenantId.get(),
                    ServiceInstance.FAILED, "The tenant could not be removed: " + error));
            log.warn("Gave up removing tenant {}: {}", tenantId.get(), error);
            return Optional.empty();
        }
        JsonNode version = payload.get().get("version");
        if (version == null) {
            log.error("Gave up on job {} on {}, which names no version", job.id(), job.queue());
            return Optional.empty();
        }
        log.warn("Gave up validating tenant {} version {}: {}", tenantId.get(), version.asInt(), error);
        return markRejected(tenantId.get(), version.asInt(), List.of("Validation did not finish: " + error));
    }

    private Optional<JsonNode> payloadOf(Job job) {
        try {
            JsonNode payload = json.readTree(job.payload());
            UUID.fromString(payload.get("tenantId").asString());
            return Optional.of(payload);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
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
            releaseUnusedHosts(tenantId);
        }
    }

    private void reject(Job job, UUID tenantId, int version, List<String> errors) {
        Optional<Tenant> tenant = transactions.execute(status -> {
            Optional<Tenant> live = markRejected(tenantId, version, errors);
            completeOrRollBack(job);
            return live;
        });
        if (tenant != null && tenant.isPresent()) {
            log.info("Tenant {} version {} rejected", tenant.get().name(), version);
            releaseAfterRejection(tenant.get());
        }
    }

    private Optional<Tenant> markRejected(UUID tenantId, int version, List<String> errors) {
        Optional<Tenant> live = tenants.lockLive(tenantId);
        live.ifPresent(t -> {
            tenants.setStatus(tenantId, version, "REJECTED", String.join("\n", errors));
            tenants.finishOperation(tenantId, ServiceInstance.FAILED,
                    "Version " + version + " was rejected: " + String.join("; ", errors));
        });
        return live;
    }

    private void releaseAfterRejection(Tenant tenant) {
        releaseUnusedHosts(tenant.id());
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
            try {
                reservations.releaseHosts(tenantId, tenants.hostsOfAllVersions(tenantId));
            } catch (RuntimeException e) {
                log.warn("Could not release the hosts of tenant {}: {}", tenant.get().name(), e.getMessage());
            }
        }
    }

    private void completeOrRollBack(Job job) {
        if (!jobs.complete(job)) {
            throw new IllegalStateException("Lost the lock on job " + job.id());
        }
    }

    private void releaseUnusedHosts(UUID tenantId) {
        try {
            transactions.executeWithoutResult(status -> {
                Optional<Tenant> live = tenants.lockLive(tenantId);
                if (live.isEmpty() || tenants.instanceOf(tenantId).map(ServiceInstance::inProgress).orElse(false)) {
                    return;
                }
                Set<String> unused = new LinkedHashSet<>(tenants.hostsOfAllVersions(tenantId));
                Integer active = live.get().activeVersion();
                if (active != null && TenantSpecParser.parse(tenants.spec(tenantId, active).orElseThrow())
                        instanceof TenantSpecParser.Result.Valid valid) {
                    unused.removeAll(valid.spec().hosts());
                }
                reservations.releaseHosts(tenantId, unused);
            });
        } catch (RuntimeException e) {
            log.warn("Could not release unused hosts of tenant {}: {}", tenantId, e.getMessage());
        }
    }
}
