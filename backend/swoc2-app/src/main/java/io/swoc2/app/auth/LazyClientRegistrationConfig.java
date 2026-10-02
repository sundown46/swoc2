package io.swoc2.app.auth;

import java.util.Map;
import java.util.function.Supplier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientProperties;
import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientPropertiesMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.util.function.SingletonSupplier;

/**
 * Spring Boot's auto-configured {@link ClientRegistrationRepository} resolves every
 * registration - including the OIDC-discovery HTTP call to Keycloak's {@code issuer-uri} - by
 * calling {@link OAuth2ClientPropertiesMapper#asClientRegistrations()} eagerly, inside its own
 * {@code @Bean} factory method, during application context startup. That means the whole app
 * refuses to boot - not even {@code /config.json} or {@code /actuator/health} come up - if
 * Keycloak happens to be unreachable or still starting when SWOC2 does, or if the OIDC env
 * vars simply haven't been set yet. Confirmed: a plain {@code docker run} of the image with no
 * Keycloak configured crashes the JVM outright instead of at least serving what doesn't need
 * auth. CLAUDE.md principle #1 ("one ... failing [dependency] must never crash the process")
 * rules that out, so this replaces it with a repository that defers the actual discovery call
 * to the first real login attempt - by which point Keycloak either answers or the login fails
 * cleanly, instead of the whole process refusing to start. {@link SingletonSupplier} memoizes
 * the result so discovery only ever runs once it succeeds.
 */
@Configuration
@EnableConfigurationProperties(OAuth2ClientProperties.class)
class LazyClientRegistrationConfig {

    @Bean
    ClientRegistrationRepository clientRegistrationRepository(OAuth2ClientProperties properties) {
        Supplier<Map<String, ClientRegistration>> registrations =
                SingletonSupplier.of(() -> new OAuth2ClientPropertiesMapper(properties).asClientRegistrations());
        return registrationId -> registrations.get().get(registrationId);
    }
}
