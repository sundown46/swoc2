package io.swoc2.app.connections;

import io.swoc2.app.audit.AuditLog;
import io.swoc2.app.settings.InstanceSettings;
import io.swoc2.pluginapi.connection.Direction;
import java.net.URI;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Connection manager API (CON-001/002, ADM-002): admins manage connections; everyone logged in can
 * see their names and health (the layer tree is grouped by connection, MAP-002).
 */
@RestController
@RequestMapping("/api/connections")
class ConnectionController {

    private final ConnectionService service;
    private final ConnectionTypeRegistry types;
    private final ObjectMapper mapper;

    ConnectionController(ConnectionService service, ConnectionTypeRegistry types, ObjectMapper mapper) {
        this.service = service;
        this.types = types;
        this.mapper = mapper;
    }

    /** Connection type catalogue with JSON Schemas for the generated forms. */
    record TypeInfo(String id, String name, String frameFormat, List<Direction> directions, JsonNode configSchema) {}

    @GetMapping("/types")
    @PreAuthorize("hasRole('ADMIN')")
    List<TypeInfo> types() {
        return types.all().stream()
                .map(t -> new TypeInfo(
                        t.id(),
                        t.name(),
                        t.frameFormat(),
                        t.directions().stream().sorted().toList(),
                        mapper.readTree(t.configSchema())))
                .toList();
    }

    /** A connection with its live status. Config is only included for admins (and masked). */
    record ConnectionView(ConnectionDefinition definition, ConnectionRuntime.Status status) {}

    @GetMapping
    @PreAuthorize("hasRole('VIEWER')")
    List<ConnectionView> list(org.springframework.security.core.Authentication auth) {
        boolean admin =
                auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        return service.runtimes().stream()
                .sorted(Comparator.comparing(r -> r.definition().name()))
                .map(r -> new ConnectionView(
                        admin ? service.masked(r.definition()) : withoutConfig(r.definition()), r.status()))
                .toList();
    }

    private static ConnectionDefinition withoutConfig(ConnectionDefinition d) {
        return new ConnectionDefinition(d.id(), d.name(), d.type(), d.enabled(), d.direction(), Map.of(), d.aging());
    }

    /** What clients send to create/update a connection. */
    record ConnectionInput(
            String name,
            String type,
            Boolean enabled,
            Direction direction,
            Map<String, Object> config,
            InstanceSettings.Aging aging) {

        ConnectionDefinition toDefinition(UUID id) {
            return new ConnectionDefinition(
                    id,
                    name,
                    type,
                    enabled == null || enabled,
                    direction == null ? Direction.IN : direction,
                    config == null ? Map.of() : config,
                    aging);
        }
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    ConnectionDefinition create(@RequestBody ConnectionInput input) {
        return service.masked(service.create(input.toDefinition(null), AuditLog.currentActorName()));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    ConnectionDefinition update(@PathVariable UUID id, @RequestBody ConnectionInput input) {
        return service.masked(service.update(id, input.toDefinition(id), AuditLog.currentActorName()));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    void delete(@PathVariable UUID id) {
        service.delete(id, AuditLog.currentActorName());
    }

    @PostMapping("/{id}/enable")
    @PreAuthorize("hasRole('ADMIN')")
    ConnectionDefinition enable(@PathVariable UUID id) {
        return service.masked(service.setEnabled(id, true, AuditLog.currentActorName()));
    }

    @PostMapping("/{id}/disable")
    @PreAuthorize("hasRole('ADMIN')")
    ConnectionDefinition disable(@PathVariable UUID id) {
        return service.masked(service.setEnabled(id, false, AuditLog.currentActorName()));
    }

    record TestRequest(String type, Map<String, Object> config, Integer seconds) {}

    /** Tries a configuration without saving it (CON-001). */
    @PostMapping("/test")
    @PreAuthorize("hasRole('ADMIN')")
    ConnectionService.TestResult test(@RequestBody TestRequest request) {
        return service.test(
                request.type(),
                request.config() == null ? Map.of() : request.config(),
                request.seconds() == null ? 5 : request.seconds());
    }

    @ExceptionHandler(InvalidConnectionException.class)
    ProblemDetail invalid(InvalidConnectionException e) {
        ProblemDetail p = problem(
                HttpStatus.BAD_REQUEST,
                "validation",
                "Invalid connection",
                e.fieldErrors().toString());
        p.setProperty("fieldErrors", e.fieldErrors());
        return p;
    }

    @ExceptionHandler(UnknownConnectionException.class)
    ProblemDetail unknown() {
        return problem(HttpStatus.NOT_FOUND, "connection-not-found", "Not found", "No such connection.");
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail unreadable() {
        return problem(HttpStatus.BAD_REQUEST, "bad-request", "Bad request", "Malformed request body");
    }

    @ExceptionHandler(org.springframework.dao.DuplicateKeyException.class)
    ProblemDetail duplicate() {
        ProblemDetail p =
                problem(HttpStatus.CONFLICT, "validation", "Invalid connection", "A connection with this name exists");
        p.setProperty("fieldErrors", Map.of("name", "already used by another connection"));
        return p;
    }

    private static ProblemDetail problem(HttpStatus status, String type, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create("https://swoc2.example/problems/" + type));
        problem.setTitle(title);
        return problem;
    }
}
