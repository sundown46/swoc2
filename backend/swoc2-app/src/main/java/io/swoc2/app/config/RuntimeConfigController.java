package io.swoc2.app.config;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /config.json} (ARCHITECTURE §12): lets one built SPA artefact work on any
 * deployment. The backend always answers on {@code /} internally; a reverse proxy on a
 * sub-path strips its prefix before forwarding (see {@code deploy/compose/Caddyfile.dev}), so
 * the browser fetches this at {@code {base}/config.json} while this controller stays unaware
 * of the base path - it only needs to report it.
 */
@RestController
class RuntimeConfigController {

    private final String basePath;

    RuntimeConfigController(@Value("${swoc2.base-path:/}") String basePath) {
        this.basePath = basePath;
    }

    @GetMapping("/config.json")
    Map<String, Object> config() {
        return Map.of("basePath", basePath);
    }
}
