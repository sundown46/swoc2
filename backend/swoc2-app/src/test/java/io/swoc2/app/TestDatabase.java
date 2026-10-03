package io.swoc2.app;

import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * One real database for every Spring test context (CLAUDE.md "Integration tests use
 * Testcontainers"): the same TimescaleDB-HA image (with PostGIS) as the dev stack and production,
 * started once per test JVM and shared. Registered for all contexts via
 * {@code META-INF/spring.factories}, so individual tests need no annotations.
 */
public class TestDatabase implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    /** Keep in sync with deploy/compose and docs/adr/0001-technology-versions.md. */
    public static final String IMAGE = "timescale/timescaledb-ha:pg18.6-ts2.30.2";

    private static final PostgreSQLContainer CONTAINER = new PostgreSQLContainer(
                    DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("swoc2")
            .withUsername("swoc2")
            .withPassword("swoc2-test");

    static {
        CONTAINER.start();
    }

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        TestPropertyValues.of(
                        "spring.datasource.url=" + CONTAINER.getJdbcUrl(),
                        "spring.datasource.username=" + CONTAINER.getUsername(),
                        "spring.datasource.password=" + CONTAINER.getPassword())
                .applyTo(context.getEnvironment());
    }
}
