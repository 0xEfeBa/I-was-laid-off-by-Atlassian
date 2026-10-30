package dev.triedge.broker.service;

import dev.triedge.broker.config.BrokerProperties;
import dev.triedge.broker.data.ServiceInstance;
import dev.triedge.broker.data.Tenant;
import dev.triedge.broker.data.TenantRepository;
import dev.triedge.common.db.ClusterContext;
import dev.triedge.common.db.ClusterProperties;
import dev.triedge.common.queue.JobQueue;
import dev.triedge.tenant.TenantSpecParser;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
public class TenantService {

    public static final String SERVICE_ID = "tri-edge-tenant";
    public static final String PLAN_ID = "standard";
    public static final String VALIDATE_QUEUE = "tenant-validate";
    public static final String DEPROVISION_QUEUE = "tenant-deprovision";

    public record Accepted(String operation, boolean done) {
    }

    public record Instance(ServiceInstance instance, Tenant tenant, JsonNode parameters) {
    }

    private final TenantRepository tenants;
    private final TenantReservations reservations;
    private final JobQueue jobs;
    private final TransactionTemplate transactions;
    private final BrokerProperties properties;
    private final JsonMapper json;
    private final String region;

    public TenantService(TenantRepository tenants, TenantReservations reservations, JobQueue jobs,
            TransactionTemplate transactions, BrokerProperties properties, JsonMapper json, ClusterProperties clusters) {
        this.tenants = tenants;
        this.reservations = reservations;
        this.jobs = jobs;
        this.transactions = transactions;
        this.properties = properties;
        this.json = json;
        this.region = clusters.region();
    }

    public Accepted provision(Actor actor, String instanceId, @Nullable String serviceId, @Nullable String planId,
            @Nullable JsonNode parameters) {
        requirePlan(serviceId, planId);
        TenantSpecParser.Result.Valid valid = parse(instanceId, parameters);
        Optional<ServiceInstance> existing = tenants.findInstance(instanceId);
        if (existing.isPresent()) {
            return repeatProvision(actor, existing.get(), valid);
        }
        String name = valid.spec().name();
        if (properties.reservedNames().contains(name)) {
            throw new TenantException(TenantException.Kind.INVALID, "'" + name + "' is reserved by the platform",
                    List.of("/name: '" + name + "' is reserved by the platform"));
        }
        if (!actor.admin() && tenants.countLiveOwnedBy(actor.id()) >= properties.maxTenantsPerUser()) {
            throw new TenantException(TenantException.Kind.LIMIT,
                    "You can own at most " + properties.maxTenantsPerUser() + " tenants");
        }
        UUID tenantId = UUID.randomUUID();
        String home = ClusterContext.current().orElse(region);
        List<String> conflicts = reservations.reserve(tenantId, name, valid.spec().hosts());
        if (!conflicts.isEmpty()) {
            throw new TenantException(TenantException.Kind.CONFLICT, String.join("; ", conflicts), conflicts);
        }
        try {
            transactions.executeWithoutResult(status -> {
                tenants.insertTenant(tenantId, name, actor.id(), home);
                tenants.insertVersion(tenantId, 1, valid.canonicalJson(), valid.hash(), actor.id(), "APPLY");
                tenants.setStatus(tenantId, 1, "VALIDATING", null);
                tenants.insertInstance(instanceId, tenantId, SERVICE_ID, PLAN_ID, valid.hash(), validating(1));
                enqueueValidation(tenantId, 1);
            });
        } catch (RuntimeException e) {
            reservations.releaseName(tenantId, name);
            reservations.releaseHosts(tenantId, List.of());
            if (e instanceof DuplicateKeyException) {
                throw new TenantException(TenantException.Kind.CONFLICT, "The tenant is being created by another request");
            }
            throw e;
        }
        return new Accepted("provision", false);
    }

    public Accepted update(Actor actor, String instanceId, @Nullable String serviceId, @Nullable JsonNode parameters) {
        if (serviceId != null && !SERVICE_ID.equals(serviceId)) {
            throw new TenantException(TenantException.Kind.INVALID, "Unknown service " + serviceId);
        }
        ServiceInstance instance = ownedInstance(actor, instanceId);
        TenantSpecParser.Result.Valid valid = parse(instanceId, parameters);
        Tenant tenant = tenants.findById(instance.tenantId()).orElseThrow();
        return submit(actor, tenant, instance, valid, "APPLY");
    }

    public Accepted deprovision(Actor actor, String instanceId, @Nullable String serviceId, @Nullable String planId) {
        requirePlan(serviceId, planId);
        Optional<ServiceInstance> found = tenants.findInstance(instanceId);
        if (found.isEmpty()) {
            throw new TenantException(TenantException.Kind.GONE, "The instance does not exist");
        }
        ServiceInstance instance = visible(actor, found.get());
        if (instance.inProgress()) {
            throw busy();
        }
        transactions.executeWithoutResult(status -> {
            tenants.startOperation(instance.tenantId(), "deprovision", instance.parametersHash(), "Removing the tenant");
            jobs.enqueue(DEPROVISION_QUEUE, "deprovision:" + instance.tenantId(),
                    "{\"tenantId\":\"" + instance.tenantId() + "\"}");
        });
        return new Accepted("deprovision", false);
    }

    public ServiceInstance lastOperation(Actor actor, String instanceId) {
        Optional<ServiceInstance> found = tenants.findInstance(instanceId);
        if (found.isEmpty()) {
            throw new TenantException(TenantException.Kind.GONE, "The instance does not exist");
        }
        return visible(actor, found.get());
    }

    public Instance fetch(Actor actor, String instanceId) {
        ServiceInstance instance = ownedInstance(actor, instanceId);
        Tenant tenant = tenants.findById(instance.tenantId()).orElseThrow();
        int version = tenant.activeVersion() != null ? tenant.activeVersion() : tenants.latestVersion(tenant.id());
        String spec = tenants.spec(tenant.id(), version).orElseThrow();
        return new Instance(instance, tenant, json.readTree(spec));
    }

    public Accepted rollback(Actor actor, String name, int version) {
        Tenant tenant = tenants.findLive(name).filter(actor::canSee)
                .orElseThrow(() -> new TenantException(TenantException.Kind.NOT_FOUND, "Tenant not found"));
        if (Integer.valueOf(version).equals(tenant.activeVersion())) {
            throw new TenantException(TenantException.Kind.INVALID, "Version " + version + " is already active");
        }
        String spec = tenants.spec(tenant.id(), version)
                .orElseThrow(() -> new TenantException(TenantException.Kind.NOT_FOUND, "Version not found"));
        ServiceInstance instance = tenants.instanceOf(tenant.id()).orElseThrow();
        if (!(TenantSpecParser.parse(spec) instanceof TenantSpecParser.Result.Valid valid)) {
            throw new TenantException(TenantException.Kind.INVALID, "Version " + version + " can no longer be used");
        }
        return submit(actor, tenant, instance, valid, "ROLLBACK");
    }

    private Accepted submit(Actor actor, Tenant tenant, ServiceInstance instance, TenantSpecParser.Result.Valid valid,
            String reason) {
        if (instance.inProgress()) {
            throw busy();
        }
        if (tenant.activeVersion() != null
                && tenants.specHash(tenant.id(), tenant.activeVersion()).orElseThrow().equals(valid.hash())) {
            return new Accepted("update", true);
        }
        List<String> conflicts = reservations.reserve(tenant.id(), null, valid.spec().hosts());
        if (!conflicts.isEmpty()) {
            throw new TenantException(TenantException.Kind.CONFLICT, String.join("; ", conflicts), conflicts);
        }
        int next = tenants.latestVersion(tenant.id()) + 1;
        try {
            transactions.executeWithoutResult(status -> {
                tenants.insertVersion(tenant.id(), next, valid.canonicalJson(), valid.hash(), actor.id(), reason);
                tenants.setStatus(tenant.id(), next, "VALIDATING", null);
                tenants.startOperation(tenant.id(), "update", valid.hash(), validating(next));
                enqueueValidation(tenant.id(), next);
            });
        } catch (DuplicateKeyException e) {
            throw busy();
        }
        return new Accepted("update", false);
    }

    private Accepted repeatProvision(Actor actor, ServiceInstance instance, TenantSpecParser.Result.Valid valid) {
        Tenant tenant = tenants.findById(instance.tenantId()).orElseThrow();
        if (!actor.canSee(tenant)) {
            throw new TenantException(TenantException.Kind.CONFLICT, "'" + instance.instanceId() + "' is already taken");
        }
        if (!"provision".equals(instance.operation()) || !instance.parametersHash().equals(valid.hash())) {
            throw new TenantException(TenantException.Kind.CONFLICT,
                    "The instance already exists with different parameters");
        }
        return new Accepted("provision", !instance.inProgress());
    }

    private ServiceInstance ownedInstance(Actor actor, String instanceId) {
        return visible(actor, tenants.findInstance(instanceId)
                .orElseThrow(() -> new TenantException(TenantException.Kind.NOT_FOUND, "The instance does not exist")));
    }

    private ServiceInstance visible(Actor actor, ServiceInstance instance) {
        Tenant tenant = tenants.findById(instance.tenantId()).orElseThrow();
        if (!actor.canSee(tenant)) {
            throw new TenantException(TenantException.Kind.NOT_FOUND, "The instance does not exist");
        }
        return instance;
    }

    private void enqueueValidation(UUID tenantId, int version) {
        jobs.enqueue(VALIDATE_QUEUE, "validate:" + tenantId + ":" + version,
                "{\"tenantId\":\"" + tenantId + "\",\"version\":" + version + "}");
    }

    private static TenantSpecParser.Result.Valid parse(String instanceId, @Nullable JsonNode parameters) {
        if (parameters == null || parameters.isNull() || parameters.isMissingNode()) {
            throw new TenantException(TenantException.Kind.INVALID, "The tenant spec is missing from parameters");
        }
        return switch (TenantSpecParser.parse(parameters)) {
            case TenantSpecParser.Result.Invalid invalid -> throw new TenantException(TenantException.Kind.INVALID,
                    String.join("; ", invalid.errors()), invalid.errors());
            case TenantSpecParser.Result.Valid valid -> {
                if (!valid.spec().name().equals(instanceId)) {
                    throw new TenantException(TenantException.Kind.INVALID,
                            "The instance id must be the tenant name '" + valid.spec().name() + "'");
                }
                yield valid;
            }
        };
    }

    private static void requirePlan(@Nullable String serviceId, @Nullable String planId) {
        if (!SERVICE_ID.equals(serviceId) || !PLAN_ID.equals(planId)) {
            throw new TenantException(TenantException.Kind.INVALID,
                    "service_id must be " + SERVICE_ID + " and plan_id must be " + PLAN_ID);
        }
    }

    private static String validating(int version) {
        return "Validating version " + version;
    }

    private static TenantException busy() {
        return new TenantException(TenantException.Kind.BUSY, "Another operation is in progress for this tenant");
    }
}
