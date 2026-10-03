package io.swoc2.app.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Metadata of the generated OpenAPI description (API-001, CLAUDE.md principle 7: the UI has no
 * private backdoor, everything is in this API). Served at {@code {base}/api/openapi.json}. The
 * realtime protocol itself is documented separately in {@code docs/realtime-protocol.md}.
 */
@Configuration
class OpenApiConfig {

    @Bean
    OpenAPI swoc2OpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("SWOC2 API")
                        .version("v1")
                        .description("REST API of SWOC2 (SEDAP Web Operated C2). Authentication: login session cookie"
                                + " (BFF, ADR 0004); state-changing requests need the X-XSRF-TOKEN header with the"
                                + " value of the XSRF-TOKEN cookie. Errors are application/problem+json (RFC 9457)."
                                + " Realtime: see docs/realtime-protocol.md."));
    }
}
