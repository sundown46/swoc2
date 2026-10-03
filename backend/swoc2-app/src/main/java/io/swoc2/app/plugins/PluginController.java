package io.swoc2.app.plugins;

import io.swoc2.app.audit.AuditLog;
import io.swoc2.pluginapi.PluginEndpoint;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Plugin REST surface: health list (ADM-008 basis), admin enable/disable (PLG-003 "can be
 * disabled at runtime") and the plugin endpoints under {@code /api/plugins/{id}/...} (PLG-002).
 */
@RestController
@RequestMapping("/api/plugins")
class PluginController {

    private static final int MAX_BODY_CHARS = 64 * 1024;

    private final PluginRegistry registry;
    private final PluginInvoker invoker;
    private final RoleHierarchy roleHierarchy;
    private final AuditLog auditLog;

    PluginController(PluginRegistry registry, PluginInvoker invoker, RoleHierarchy roleHierarchy, AuditLog auditLog) {
        this.registry = registry;
        this.invoker = invoker;
        this.roleHierarchy = roleHierarchy;
        this.auditLog = auditLog;
    }

    @GetMapping
    @PreAuthorize("hasRole('VIEWER')")
    List<PluginHandle.PluginHealth> list() {
        return registry.all().stream().map(PluginHandle::health).toList();
    }

    @PostMapping("/{id}/enable")
    @PreAuthorize("hasRole('ADMIN')")
    ResponseEntity<PluginHandle.PluginHealth> enable(@PathVariable String id, Authentication who) {
        PluginHandle handle = registry.get(id).orElseThrow(UnknownPluginException::new);
        PluginState before = handle.state();
        registry.enable(id);
        auditLog.record("plugin.enable", "plugin", id, Map.of("state", before), Map.of("state", handle.state()));
        return ResponseEntity.ok(handle.health());
    }

    @PostMapping("/{id}/disable")
    @PreAuthorize("hasRole('ADMIN')")
    ResponseEntity<PluginHandle.PluginHealth> disable(@PathVariable String id, Authentication who) {
        PluginHandle handle = registry.get(id).orElseThrow(UnknownPluginException::new);
        PluginState before = handle.state();
        registry.disable(id);
        auditLog.record("plugin.disable", "plugin", id, Map.of("state", before), Map.of("state", handle.state()));
        return ResponseEntity.ok(handle.health());
    }

    @GetMapping("/{id}/endpoints/**")
    Object get(@PathVariable String id, HttpServletRequest request, Authentication who) {
        return call(id, PluginEndpoint.Method.GET, request, "", who);
    }

    @PostMapping("/{id}/endpoints/**")
    Object post(
            @PathVariable String id,
            HttpServletRequest request,
            @RequestBody(required = false) String body,
            Authentication who) {
        String payload = body == null ? "" : body;
        if (payload.length() > MAX_BODY_CHARS) {
            throw new IllegalArgumentException("Request body too large");
        }
        Object result = call(id, PluginEndpoint.Method.POST, request, payload, who);
        auditLog.record("plugin.call", "plugin", id, null, null, Map.of("path", request.getRequestURI()));
        return result;
    }

    private Object call(
            String id, PluginEndpoint.Method method, HttpServletRequest request, String body, Authentication who) {
        PluginHandle handle = registry.get(id).orElseThrow(UnknownPluginException::new);
        String prefix = "/api/plugins/" + id + "/endpoints/";
        String uri = request.getRequestURI();
        int at = uri.indexOf(prefix);
        String path = at < 0 ? "" : uri.substring(at + prefix.length());
        PluginEndpoint endpoint = registry.endpoint(handle, method, path).orElseThrow(UnknownPluginException::new);
        String role = "ROLE_" + String.valueOf(endpoint.requiredRole()).toUpperCase();
        Collection<? extends GrantedAuthority> reachable =
                roleHierarchy.getReachableGrantedAuthorities(who.getAuthorities());
        if (reachable.stream().noneMatch(a -> role.equals(a.getAuthority()))) {
            throw new org.springframework.security.access.AccessDeniedException("Plugin endpoint requires " + role);
        }
        Map<String, String> query = new LinkedHashMap<>();
        request.getParameterMap().forEach((k, v) -> query.put(k, v.length > 0 ? v[0] : ""));
        return invoker.invoke(handle, method + " " + path, () -> endpoint.handle(Map.copyOf(query), body));
    }

    static class UnknownPluginException extends RuntimeException {}

    @ExceptionHandler(UnknownPluginException.class)
    ProblemDetail unknown() {
        return problem(HttpStatus.NOT_FOUND, "plugin-not-found", "Not found", "No such plugin or plugin endpoint.");
    }

    @ExceptionHandler(PluginCallException.class)
    ProblemDetail pluginFailed(PluginCallException e) {
        return switch (e.reason()) {
            case NOT_ENABLED ->
                problem(HttpStatus.SERVICE_UNAVAILABLE, "plugin-disabled", "Plugin disabled", e.getMessage());
            case TIMEOUT -> problem(HttpStatus.GATEWAY_TIMEOUT, "plugin-timeout", "Plugin timed out", e.getMessage());
            case FAILED -> problem(HttpStatus.BAD_GATEWAY, "plugin-failed", "Plugin failed", e.getMessage());
        };
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail badRequest(IllegalArgumentException e) {
        return problem(HttpStatus.BAD_REQUEST, "bad-request", "Bad request", e.getMessage());
    }

    private static ProblemDetail problem(HttpStatus status, String type, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create("https://swoc2.example/problems/" + type));
        problem.setTitle(title);
        return problem;
    }
}
