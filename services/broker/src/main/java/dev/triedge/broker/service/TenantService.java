package dev.triedge.broker.service;

import dev.triedge.broker.config.BrokerProperties;
import dev.triedge.broker.data.ServiceInstance;
import dev.triedge.broker.data.Tenant;
import dev.triedge.broker.data.TenantRepository;
import dev.triedge.broker.data.TenantVersion;
import dev.triedge.common.db.ClusterContext;
import dev.triedge.common.db.ClusterProperties;
import dev.triedge.common.queue.JobQueue;
import dev.triedge.tenant.TenantSpecParser;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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

    private static final String PROVISION = "provision";
    private static final String UPDATE = "update";
    private static final String DEPROVISION = "deprovision";
    private static final Pattern OPERATION = Pattern.compile("(provision|update):([1-9][0-9]{0,8})");

    public record Accepted(String operation, boolean done) {
    }

    public record Operation(String state, @Nullable String description) {
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
        requireReserved(reservations.reserve(tenantId, name, valid.spec().hosts()));
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
            reservations.releaseHosts(tenantId, valid.spec().hosts());
            if (e instanceof DuplicateKeyException) {
                throw new TenantException(TenantException.Kind.CONFLICT, "The tenant is being created by another request");
            }
            throw e;
        }
        return new Accepted(PROVISION + ":1", false);
    }

    public Accepted update(Actor actor, String instanceId, @Nullable String serviceId, @Nullable JsonNode parameters) {
        if (serviceId != null && !SERVICE_ID.equals(serviceId)) {
            throw new TenantException(TenantException.Kind.INVALID, "Unknown service " + serviceId);
        }
        ServiceInstance instance = ownedInstance(actor, instanceId);
        TenantSpecParser.Result.Valid valid = parse(instanceId, parameters);
        return submit(actor, instance.tenantId(), valid, null);
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
            tenants.startOperation(instance.tenantId(), DEPROVISION, instance.parametersHash(), "Removing the tenant");
            jobs.enqueue(DEPROVISION_QUEUE, "deprovision:" + instance.tenantId(),
                    "{\"tenantId\":\"" + instance.tenantId() + "\"}");
        });
        return new Accepted(DEPROVISION, false);
    }

    public Operation lastOperation(Actor actor, String instanceId, @Nullable String operation) {
        Optional<ServiceInstance> found = tenants.findInstance(instanceId);
        if (found.isEmpty()) {
            throw new TenantException(TenantException.Kind.GONE, "The instance does not exist");
        }
        ServiceInstance instance = visible(actor, found.get());
        Operation current = new Operation(instance.operationState(), instance.operationDescription());
        if (operation == null || operation.equals(DEPROVISION)) {
            return current;
        }
        Matcher matcher = OPERATION.matcher(operation);
        if (!matcher.matches() || (matcher.group(1).equals(PROVISION) != matcher.group(2).equals("1"))) {
            throw new TenantException(TenantException.Kind.INVALID, "Unknown operation '" + operation + "'");
        }
        int version = Integer.parseInt(matcher.group(2));
        if (matcher.group(1).equals(instance.operation()) && version == tenants.latestVersion(instance.tenantId())) {
            return current;
        }
        TenantVersion row = tenants.version(instance.tenantId(), version)
                .orElseThrow(() -> new TenantException(TenantException.Kind.INVALID, "Unknown operation '" + operation + "'"));
        return switch (String.valueOf(row.state())) {
            case "VALIDATING" -> new Operation(ServiceInstance.IN_PROGRESS, validating(version));
            case "ACTIVE" -> new Operation(ServiceInstance.SUCCEEDED, "Version " + version + " is active");
            case "SUPERSEDED" -> new Operation(ServiceInstance.SUCCEEDED,
                    "Version " + version + " was active and has been replaced");
            default -> new Operation(ServiceInstance.FAILED,
                    "Version " + version + " was rejected: " + String.valueOf(row.detail()).replace("\n", "; "));
        };
    }

    public Instance fetch(Actor actor, String instanceId) {
        ServiceInstance instance = ownedInstance(actor, instanceId);
        Tenant tenant = live(instance.tenantId());
        if (tenant.activeVersion() == null) {
            throw new TenantException(TenantException.Kind.NOT_FOUND, "The instance is not ready yet");
        }
        if (instance.inProgress() && UPDATE.equals(instance.operation())) {
            throw new TenantException(TenantException.Kind.BUSY, "The instance is being updated");
        }
        String spec = tenants.spec(tenant.id(), tenant.activeVersion()).orElseThrow();
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
        if (!(TenantSpecParser.parse(spec) instanceof TenantSpecParser.Result.Valid valid)) {
            throw new TenantException(TenantException.Kind.INVALID, "Version " + version + " can no longer be used");
        }
        return submit(actor, tenant.id(), valid, version);
    }

    private Accepted submit(Actor actor, UUID tenantId, TenantSpecParser.Result.Valid valid,
            @Nullable Integer rollbackTo) {
        List<String> created = new ArrayList<>();
        try {
            return Objects.requireNonNull(transactions.execute(status -> {
                Tenant tenant = tenants.lockLive(tenantId)
                        .orElseThrow(() -> new TenantException(TenantException.Kind.NOT_FOUND, "Tenant not found"));
                if (tenants.instanceOf(tenantId).orElseThrow().inProgress()) {
                    throw busy();
                }
                if (tenant.activeVersion() != null
                        && tenants.specHash(tenantId, tenant.activeVersion()).orElseThrow().equals(valid.hash())) {
                    return new Accepted(UPDATE, true);
                }
                created.addAll(requireReserved(reservations.reserve(tenantId, null, valid.spec().hosts())));
                int next = tenants.latestVersion(tenantId) + 1;
                tenants.insertVersion(tenantId, next, valid.canonicalJson(), valid.hash(), actor.id(),
                        rollbackTo == null ? "APPLY" : "ROLLBACK");
                tenants.setStatus(tenantId, next, "VALIDATING", null);
                tenants.startOperation(tenantId, UPDATE, valid.hash(), rollbackTo == null
                        ? validating(next)
                        : "Rolling back to version " + rollbackTo + ": " + validating(next).toLowerCase(Locale.ROOT));
                enqueueValidation(tenantId, next);
                return new Accepted(UPDATE + ":" + next, false);
            }));
        } catch (RuntimeException e) {
            if (!created.isEmpty()) {
                reservations.releaseHosts(tenantId, created);
            }
            if (e instanceof DuplicateKeyException) {
                throw busy();
            }
            throw e;
        }
    }

    private static List<String> requireReserved(TenantReservations.Reserved reserved) {
        if (!reserved.conflicts().isEmpty()) {
            throw new TenantException(TenantException.Kind.CONFLICT, String.join("; ", reserved.conflicts()),
                    reserved.conflicts());
        }
        return reserved.createdHosts();
    }

    private Accepted repeatProvision(Actor actor, ServiceInstance instance, TenantSpecParser.Result.Valid valid) {
        Tenant tenant = live(instance.tenantId());
        if (!actor.canSee(tenant)) {
            throw new TenantException(TenantException.Kind.CONFLICT, "'" + instance.instanceId() + "' is already taken");
        }
        if (!PROVISION.equals(instance.operation()) || !instance.parametersHash().equals(valid.hash())) {
            throw new TenantException(TenantException.Kind.CONFLICT,
                    "The instance already exists with different parameters");
        }
        return new Accepted(PROVISION + ":1", !instance.inProgress());
    }

    private ServiceInstance ownedInstance(Actor actor, String instanceId) {
        return visible(actor, tenants.findInstance(instanceId)
                .orElseThrow(() -> new TenantException(TenantException.Kind.NOT_FOUND, "The instance does not exist")));
    }

    private ServiceInstance visible(Actor actor, ServiceInstance instance) {
        Tenant tenant = live(instance.tenantId());
        if (!actor.canSee(tenant)) {
            throw new TenantException(TenantException.Kind.NOT_FOUND, "The instance does not exist");
        }
        return instance;
    }

    private Tenant live(UUID tenantId) {
        return tenants.findLiveById(tenantId)
                .orElseThrow(() -> new TenantException(TenantException.Kind.NOT_FOUND, "The instance does not exist"));
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
