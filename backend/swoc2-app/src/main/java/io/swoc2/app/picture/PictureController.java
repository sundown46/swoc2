package io.swoc2.app.picture;

import io.swoc2.app.audit.AuditLog;
import io.swoc2.domain.picture.Contact;
import io.swoc2.domain.picture.ContactOverride;
import io.swoc2.domain.picture.ContactState;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST access to the live picture (API-first, CLAUDE.md principle 7). Clients normally receive
 * the picture via realtime topics (M4); these endpoints serve queries, the CAC and admin actions.
 */
@RestController
@RequestMapping("/api/picture")
class PictureController {

    /** Hard cap for list responses; full pictures go over realtime snapshots. */
    private static final int MAX_LIST = 100_000;

    private final PictureStore store;
    private final OverrideService overrides;
    private final AuditLog auditLog;

    PictureController(PictureStore store, OverrideService overrides, AuditLog auditLog) {
        this.store = store;
        this.overrides = overrides;
        this.auditLog = auditLog;
    }

    /** Contacts, optionally limited to MGRS 100 km cells (comma-separated, e.g. {@code 32UME,32UMD}). */
    @GetMapping("/contacts")
    @PreAuthorize("hasRole('VIEWER')")
    List<Contact> list(
            @RequestParam(required = false) List<String> cells, @RequestParam(defaultValue = "10000") int limit) {
        if (limit < 1 || limit > MAX_LIST) {
            throw new IllegalArgumentException("limit must be 1.." + MAX_LIST);
        }
        if (cells != null && cells.size() > 1000) {
            throw new IllegalArgumentException("at most 1000 cells");
        }
        List<Contact> contacts = cells == null ? store.all() : store.inCells(cells);
        return contacts.size() > limit ? contacts.subList(0, limit) : contacts;
    }

    @GetMapping("/contacts/{id}")
    @PreAuthorize("hasRole('VIEWER')")
    Contact get(@PathVariable UUID id) {
        return store.get(id).orElseThrow(UnknownContactException::new);
    }

    /** Totals and the highest classification of displayed data (GEN-011 banner). */
    @GetMapping("/summary")
    @PreAuthorize("hasRole('VIEWER')")
    Map<String, Object> summary() {
        List<Contact> all = store.all();
        long stale = all.stream().filter(c -> c.state() == ContactState.STALE).count();
        Character highest = store.highestClassification();
        return Map.of(
                "total",
                all.size(),
                "live",
                all.size() - stale,
                "stale",
                stale,
                "highestClassification",
                highest == null ? "" : highest.toString());
    }

    /** Operator edit of descriptive fields, shared with everyone (PIC-003, AUTH-005). */
    @PutMapping("/contacts/{id}/override")
    @PreAuthorize("hasRole('OPERATOR')")
    Contact setOverride(@PathVariable UUID id, @RequestBody ContactOverride override) {
        Contact contact = store.get(id).orElseThrow(UnknownContactException::new);
        return overrides.set(contact, override, AuditLog.currentActorName());
    }

    @DeleteMapping("/contacts/{id}/override")
    @PreAuthorize("hasRole('OPERATOR')")
    Contact resetOverride(@PathVariable UUID id) {
        Contact contact = store.get(id).orElseThrow(UnknownContactException::new);
        return overrides.reset(contact);
    }

    /**
     * Wipes the live picture (PIC-005, ADM-005). Requires {@code {"confirm":"WIPE"}} as an explicit
     * second step. Operator overrides are kept by default (they re-apply when the same tracks
     * reappear); {@code clearOverrides: true} removes them too. User-created contacts, plans, master
     * data and history are never touched.
     */
    @PostMapping("/wipe")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    Map<String, Object> wipe(@RequestBody WipeRequest request) {
        if (request == null || !"WIPE".equals(request.confirm())) {
            throw new IllegalArgumentException("Confirm the wipe with {\"confirm\":\"WIPE\"}");
        }
        int clearedOverrides = Boolean.TRUE.equals(request.clearOverrides()) ? overrides.clearAll() : 0;
        int removed = store.wipe();
        auditLog.record(
                "picture.wipe",
                "picture",
                null,
                null,
                null,
                Map.of("removedContacts", removed, "clearedOverrides", clearedOverrides));
        return Map.of("removedContacts", removed, "clearedOverrides", clearedOverrides);
    }

    /** {@code clearOverrides} is optional (Jackson 3 rejects missing primitives, hence Boolean). */
    record WipeRequest(String confirm, Boolean clearOverrides) {}

    static class UnknownContactException extends RuntimeException {}

    @ExceptionHandler(UnknownContactException.class)
    ProblemDetail unknown() {
        return problem(
                HttpStatus.NOT_FOUND, "contact-not-found", "Contact not found", "No such contact in the live picture.");
    }

    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class})
    ProblemDetail badRequest(Exception e) {
        String detail = e instanceof HttpMessageNotReadableException ? rootMessage(e) : e.getMessage();
        return problem(HttpStatus.BAD_REQUEST, "bad-request", "Bad request", detail);
    }

    /** The validation message from a record constructor, without Jackson's wrapping. */
    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t instanceof IllegalArgumentException ? t.getMessage() : "Malformed request body";
    }

    private static ProblemDetail problem(HttpStatus status, String type, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create("https://swoc2.example/problems/" + type));
        problem.setTitle(title);
        return problem;
    }
}
