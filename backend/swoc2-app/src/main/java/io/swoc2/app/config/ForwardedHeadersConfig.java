package io.swoc2.app.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.ForwardedHeaderFilter;

/**
 * Trusts {@code X-Forwarded-*} / {@code Forwarded} (GEN-004) so redirects generated behind a
 * reverse proxy - in particular the OAuth2 login callback URL (ADR 0004) - come out with the
 * proxy's scheme/host/port and, via {@code X-Forwarded-Prefix}, its sub-path prefix. Toggled by
 * {@code SWOC2_FORWARDED_HEADERS}; disable only when SWOC2 is reached directly with no proxy in
 * front of it, since a direct client can otherwise spoof these headers.
 */
@Configuration
class ForwardedHeadersConfig {

    @Bean
    @ConditionalOnProperty(name = "swoc2.forwarded-headers", havingValue = "true", matchIfMissing = true)
    FilterRegistrationBean<ForwardedHeaderFilter> forwardedHeaderFilter() {
        var registration = new FilterRegistrationBean<>(new ForwardedHeaderFilter());
        registration.setOrder(-9999); // ahead of other filters, incl. the security filter chain
        return registration;
    }
}
