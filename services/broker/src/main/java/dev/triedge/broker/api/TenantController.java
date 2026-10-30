package dev.triedge.broker.api;

import dev.triedge.broker.service.Actor;
import dev.triedge.broker.service.TenantException;
import dev.triedge.broker.service.TenantQueries;
import dev.triedge.broker.service.TenantService;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tenants")
public class TenantController {

    public record RollbackRequest(int version) {
    }

    private final TenantQueries queries;
    private final TenantService service;

    public TenantController(TenantQueries queries, TenantService service) {
        this.queries = queries;
        this.service = service;
    }

    @GetMapping
    public List<TenantQueries.Summary> list(Caller caller) {
        return queries.list(actor(caller));
    }

    @GetMapping("/{name}")
    public TenantQueries.Detail detail(@PathVariable String name, Caller caller) {
        return queries.detail(actor(caller), name);
    }

    @GetMapping("/{name}/versions/{version}")
    public TenantQueries.VersionSpec version(@PathVariable String name, @PathVariable int version, Caller caller) {
        return queries.version(actor(caller), name, version);
    }

    @GetMapping("/{name}/diff")
    public TenantQueries.Diff diff(@PathVariable String name, @RequestParam int from, @RequestParam int to,
            Caller caller) {
        return queries.diff(actor(caller), name, from, to);
    }

    @PostMapping("/{name}/rollback")
    public ResponseEntity<Map<String, Object>> rollback(@PathVariable String name, @RequestBody RollbackRequest request,
            Caller caller) {
        TenantService.Accepted accepted = service.rollback(actor(caller), name, request.version());
        return ResponseEntity.accepted().body(Map.of("operation", accepted.operation()));
    }

    @ExceptionHandler(TenantException.class)
    public ProblemDetail tenantError(TenantException e) {
        HttpStatus status = switch (e.kind()) {
            case INVALID -> HttpStatus.BAD_REQUEST;
            case CONFLICT -> HttpStatus.CONFLICT;
            case NOT_FOUND, GONE -> HttpStatus.NOT_FOUND;
            case BUSY -> HttpStatus.CONFLICT;
            case LIMIT -> HttpStatus.FORBIDDEN;
        };
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, e.getMessage());
        if (!e.errors().isEmpty()) {
            problem.setProperty("errors", e.errors());
        }
        return problem;
    }

    private static Actor actor(Caller caller) {
        return new Actor(caller.id(), caller.admin());
    }
}
