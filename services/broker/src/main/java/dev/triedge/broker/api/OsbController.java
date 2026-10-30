package dev.triedge.broker.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.triedge.broker.data.ServiceInstance;
import dev.triedge.broker.service.Actor;
import dev.triedge.broker.service.TenantException;
import dev.triedge.broker.service.TenantService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/v2")
public class OsbController {

    public static final String API_VERSION_HEADER = "X-Broker-API-Version";

    static final int MIN_MINOR_VERSION = 12;

    private static final Pattern VERSION = Pattern.compile("2\\.(\\d{1,3})");

    private static final Map<String, Object> CATALOG = Map.of("services", List.of(Map.of(
            "id", TenantService.SERVICE_ID,
            "name", "tenant",
            "description", "Route traffic for your service through the tri-edge platform",
            "bindable", false,
            "instances_retrievable", true,
            "plan_updateable", false,
            "plans", List.of(Map.of(
                    "id", TenantService.PLAN_ID,
                    "name", "standard",
                    "description", "Up to 3 hosts, 5 backends and 30 routes",
                    "free", true)))));

    public record ProvisionRequest(@JsonProperty("service_id") @Nullable String serviceId,
            @JsonProperty("plan_id") @Nullable String planId, @Nullable JsonNode parameters) {
    }

    public record UpdateRequest(@JsonProperty("service_id") @Nullable String serviceId,
            @Nullable JsonNode parameters) {
    }

    static final class OsbException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final HttpStatus status;
        private final @Nullable String error;

        OsbException(HttpStatus status, @Nullable String error, String description) {
            super(description);
            this.status = status;
            this.error = error;
        }
    }

    private final TenantService service;

    public OsbController(TenantService service) {
        this.service = service;
    }

    @GetMapping("/catalog")
    public Map<String, Object> catalog(@RequestHeader(name = API_VERSION_HEADER, required = false) @Nullable String version) {
        requireVersion(version);
        return CATALOG;
    }

    @PutMapping("/service_instances/{instanceId}")
    public ResponseEntity<Map<String, Object>> provision(
            @RequestHeader(name = API_VERSION_HEADER, required = false) @Nullable String version,
            @PathVariable String instanceId,
            @RequestParam(name = "accepts_incomplete", defaultValue = "false") boolean acceptsIncomplete,
            @RequestBody ProvisionRequest request, Caller caller) {
        requireVersion(version);
        requireAsync(acceptsIncomplete);
        return accepted(service.provision(actor(caller), instanceId, request.serviceId(), request.planId(),
                request.parameters()));
    }

    @PatchMapping("/service_instances/{instanceId}")
    public ResponseEntity<Map<String, Object>> update(
            @RequestHeader(name = API_VERSION_HEADER, required = false) @Nullable String version,
            @PathVariable String instanceId,
            @RequestParam(name = "accepts_incomplete", defaultValue = "false") boolean acceptsIncomplete,
            @RequestBody UpdateRequest request, Caller caller) {
        requireVersion(version);
        requireAsync(acceptsIncomplete);
        return accepted(service.update(actor(caller), instanceId, request.serviceId(), request.parameters()));
    }

    @DeleteMapping("/service_instances/{instanceId}")
    public ResponseEntity<Map<String, Object>> deprovision(
            @RequestHeader(name = API_VERSION_HEADER, required = false) @Nullable String version,
            @PathVariable String instanceId,
            @RequestParam(name = "service_id", required = false) @Nullable String serviceId,
            @RequestParam(name = "plan_id", required = false) @Nullable String planId,
            @RequestParam(name = "accepts_incomplete", defaultValue = "false") boolean acceptsIncomplete,
            Caller caller) {
        requireVersion(version);
        requireAsync(acceptsIncomplete);
        return accepted(service.deprovision(actor(caller), instanceId, serviceId, planId));
    }

    @GetMapping("/service_instances/{instanceId}")
    public Map<String, Object> fetch(
            @RequestHeader(name = API_VERSION_HEADER, required = false) @Nullable String version,
            @PathVariable String instanceId, Caller caller) {
        requireVersion(version);
        TenantService.Instance instance = service.fetch(actor(caller), instanceId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("service_id", instance.instance().serviceId());
        body.put("plan_id", instance.instance().planId());
        body.put("parameters", instance.parameters());
        return body;
    }

    @GetMapping("/service_instances/{instanceId}/last_operation")
    public Map<String, Object> lastOperation(
            @RequestHeader(name = API_VERSION_HEADER, required = false) @Nullable String version,
            @PathVariable String instanceId, Caller caller) {
        requireVersion(version);
        ServiceInstance instance = service.lastOperation(actor(caller), instanceId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("state", instance.operationState());
        if (instance.operationDescription() != null) {
            body.put("description", instance.operationDescription());
        }
        return body;
    }

    @ExceptionHandler(OsbException.class)
    public ResponseEntity<Map<String, Object>> osbError(OsbException e) {
        return error(e.status, e.error, String.valueOf(e.getMessage()));
    }

    @ExceptionHandler(TenantException.class)
    public ResponseEntity<Map<String, Object>> tenantError(TenantException e) {
        String description = String.valueOf(e.getMessage());
        return switch (e.kind()) {
            case INVALID -> error(HttpStatus.BAD_REQUEST, null, description);
            case CONFLICT -> error(HttpStatus.CONFLICT, null, description);
            case NOT_FOUND -> error(HttpStatus.NOT_FOUND, null, description);
            case BUSY -> error(HttpStatus.UNPROCESSABLE_CONTENT, "ConcurrencyError", description);
            case LIMIT -> error(HttpStatus.FORBIDDEN, null, description);
            case GONE -> ResponseEntity.status(HttpStatus.GONE).body(Map.of());
        };
    }

    private static ResponseEntity<Map<String, Object>> accepted(TenantService.Accepted accepted) {
        if (accepted.done()) {
            return ResponseEntity.ok(Map.of());
        }
        return ResponseEntity.accepted().body(Map.of("operation", accepted.operation()));
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, @Nullable String error,
            String description) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (error != null) {
            body.put("error", error);
        }
        body.put("description", description);
        return ResponseEntity.status(status).body(body);
    }

    private static Actor actor(Caller caller) {
        return new Actor(caller.id(), caller.admin());
    }

    private static void requireVersion(@Nullable String version) {
        Matcher matcher = VERSION.matcher(version == null ? "" : version.trim());
        if (!matcher.matches() || Integer.parseInt(matcher.group(1)) < MIN_MINOR_VERSION) {
            throw new OsbException(HttpStatus.PRECONDITION_FAILED, null,
                    API_VERSION_HEADER + " 2." + MIN_MINOR_VERSION + " or later is required");
        }
    }

    private static void requireAsync(boolean acceptsIncomplete) {
        if (!acceptsIncomplete) {
            throw new OsbException(HttpStatus.UNPROCESSABLE_CONTENT, "AsyncRequired",
                    "This service plan requires client support for asynchronous operations");
        }
    }
}
