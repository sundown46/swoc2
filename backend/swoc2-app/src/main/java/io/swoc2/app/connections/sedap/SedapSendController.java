package io.swoc2.app.connections.sedap;

import io.swoc2.app.audit.AuditLog;
import io.swoc2.sedap.codec.DecodeResult;
import io.swoc2.sedap.codec.DecodeWarning;
import io.swoc2.sedap.codec.SedapDecoder;
import java.net.URI;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin tool: send one SEDAP-Express message (diagnostics, interoperability tests). The message
 * must decode without any warning; header number, time and sender are replaced by ours. Every send
 * is audited. Chat (M7) and tasking (P2) get their own validated endpoints.
 */
@RestController
@RequestMapping("/api/sedap")
class SedapSendController {

    private final SedapOutbound outbound;
    private final AuditLog auditLog;
    private static final java.util.Set<String> REPLACED_HEADER_FIELDS = java.util.Set.of("Number", "Time", "MAC");
    private final SedapDecoder decoder = new SedapDecoder(64 * 1024);

    SedapSendController(SedapOutbound outbound, AuditLog auditLog) {
        this.outbound = outbound;
        this.auditLog = auditLog;
    }

    /** @param connectionId target connection, or null for every outbound connection */
    record SendRequest(String message, UUID connectionId) {}

    @PostMapping("/send")
    @PreAuthorize("hasRole('ADMIN')")
    SedapOutbound.Sent send(@RequestBody SendRequest request) {
        if (request == null || request.message() == null || request.message().isBlank()) {
            throw new IllegalArgumentException("message is required");
        }
        DecodeResult decoded = decoder.decode(request.message());
        // Number, time and MAC are replaced on sending, so problems there do not matter.
        var relevant = decoded.warnings().stream()
                .filter(w -> w.field() == null || !REPLACED_HEADER_FIELDS.contains(w.field()))
                .toList();
        if (decoded.rejected() || !relevant.isEmpty()) {
            throw new IllegalArgumentException("Message is not valid SEDAP-Express: "
                    + relevant.stream().map(DecodeWarning::toString).collect(Collectors.joining("; ")));
        }
        SedapOutbound.Target target = request.connectionId() == null
                ? new SedapOutbound.AllConnections(null)
                : new SedapOutbound.Connection(request.connectionId());
        SedapOutbound.Sent sent = outbound.send(decoded.message(), target);
        auditLog.record(
                "sedap.send",
                "sedap-message",
                decoded.message().type().name(),
                null,
                null,
                Map.of("line", sent.line(), "deliveredTo", sent.deliveredTo(), "failed", sent.failed()));
        return sent;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail invalid(IllegalArgumentException e) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        p.setType(URI.create("https://swoc2.example/problems/bad-request"));
        p.setTitle("Bad request");
        return p;
    }
}
