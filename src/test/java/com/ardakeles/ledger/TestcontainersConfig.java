package com.ardakeles.ledger;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Real PostgreSQL for every integration test -- never H2. H2's locking and constraint-timing
 * semantics differ from Postgres (e.g. deferred constraint triggers do not exist), so tests
 * against it would prove nothing about this system.
 *
 * <p>One container serves the whole test JVM. It is started once in a static initializer and NO Spring
 * context owns it: contexts only receive its connection properties. (An earlier version exposed it as a
 * {@code @ServiceConnection} bean, and Spring Boot stops such a container when the context that references
 * it closes -- so one {@code @DirtiesContext} test silently killed the database under every other cached
 * context.) Testcontainers' Ryuk sidecar removes it when the test JVM exits. Flyway then migrates it on
 * each context's startup, so tests exercise the same migrations as production.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfig {

    // Keep in sync with <postgres.image> in pom.xml and docker-compose.yml.
    public static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse("postgres:17-alpine");

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(POSTGRES_IMAGE)
            // fsync=off: speed (this is the container's default; withCommand replaces it, so restated).
            //   It makes COMMIT unrealistically cheap, so these containers must not be used for benchmarks.
            // deadlock_timeout=100ms: Postgres only looks for a deadlock after a lock wait lasts this
            //   long (default 1s). A shorter value finds the same real cycles sooner; it cannot create
            //   false deadlocks. Keeps the deadlock negative-control test fast.
            // max_connections=200: several cached Spring contexts each keep their own connection pool open.
            .withCommand("postgres", "-c", "fsync=off", "-c", "deadlock_timeout=100ms", "-c", "max_connections=200");

    static {
        POSTGRES.start();
    }

    @Bean
    DynamicPropertyRegistrar postgresConnection() {
        return registry -> {
            registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
            registry.add("spring.datasource.username", POSTGRES::getUsername);
            registry.add("spring.datasource.password", POSTGRES::getPassword);
        };
    }
}
