package io.swoc2.app.connections.debug;

import io.swoc2.app.settings.InstanceSettingsService;
import java.util.List;
import java.util.Locale;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Debug console data (DBG-001/002): recent raw/parsed messages. Only available if enabled in the
 * instance settings and for the configured roles.
 */
@RestController
@RequestMapping("/api/debug")
class DebugController {

    private final DebugTap tap;
    private final InstanceSettingsService settings;

    DebugController(DebugTap tap, InstanceSettingsService settings) {
        this.tap = tap;
        this.settings = settings;
    }

    @GetMapping("/messages")
    List<DebugTap.Entry> messages(
            Authentication auth,
            @RequestParam(required = false) String connectionId,
            @RequestParam(defaultValue = "200") int limit) {
        var console = settings.current().debugConsole();
        boolean allowed = console.enabled()
                && auth.getAuthorities().stream()
                        .anyMatch(a -> console.roles()
                                .contains(a.getAuthority().replace("ROLE_", "").toLowerCase(Locale.ROOT)));
        if (!allowed) {
            throw new AccessDeniedException("The debug console is not enabled for your role");
        }
        if (limit < 1 || limit > DebugTap.CAPACITY) {
            throw new IllegalArgumentException("limit must be 1.." + DebugTap.CAPACITY);
        }
        return tap.recent(connectionId, limit);
    }
}
