package dev.triedge.controlplane.api;

import dev.triedge.controlplane.source.TenantDirectory;
import dev.triedge.controlplane.validate.ValidationService;
import dev.triedge.controlplane.xds.AckTracker;
import dev.triedge.controlplane.xds.SnapshotPublisher;
import dev.triedge.tenant.TenantSpecParser;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
public class InternalController {

    public record Status(long version, int connectedNodes, int tenants, List<AckTracker.Nack> recentNacks) {
    }

    private final ValidationService validation;
    private final SnapshotPublisher publisher;
    private final AckTracker tracker;
    private final TenantDirectory directory;

    public InternalController(ValidationService validation, SnapshotPublisher publisher, AckTracker tracker,
            TenantDirectory directory) {
        this.validation = validation;
        this.publisher = publisher;
        this.tracker = tracker;
        this.directory = directory;
    }

    @PostMapping("/internal/v1/validate")
    public ValidationService.Outcome validate(@RequestBody JsonNode spec) {
        return switch (TenantSpecParser.parse(spec)) {
            case TenantSpecParser.Result.Invalid invalid -> new ValidationService.Outcome(false, invalid.errors());
            case TenantSpecParser.Result.Valid valid -> validation.validate(valid.spec());
        };
    }

    @GetMapping("/internal/v1/status")
    public Status status() {
        return new Status(publisher.version(), tracker.connectedNodes(), directory.all().size(), tracker.recentNacks());
    }
}
