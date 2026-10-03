package io.swoc2.app.settings;

import io.swoc2.app.audit.AuditLog;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/settings/instance}: everyone logged in can read (clients need the own position and
 * sender ID), only admins can change (ADM-003).
 */
@RestController
@RequestMapping("/api/settings/instance")
class InstanceSettingsController {

    private final InstanceSettingsService service;

    InstanceSettingsController(InstanceSettingsService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasRole('VIEWER')")
    InstanceSettings get() {
        return service.current();
    }

    @PutMapping
    @PreAuthorize("hasRole('ADMIN')")
    InstanceSettings put(@Valid @RequestBody InstanceSettings settings) {
        return service.update(settings, AuditLog.currentActorName());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail invalid(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getAllErrors().stream()
                .map(err -> (err instanceof org.springframework.validation.FieldError f ? f.getField() + ": " : "")
                        + err.getDefaultMessage())
                .collect(Collectors.joining("; "));
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        problem.setType(URI.create("https://swoc2.example/problems/validation"));
        problem.setTitle("Invalid settings");
        return problem;
    }
}
