package com.ardakeles.ledger;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Real PostgreSQL for every integration test -- never H2. H2's locking and constraint-timing
 * semantics differ from Postgres (e.g. deferred constraint triggers do not exist), so tests
 * against it would prove nothing about this system.
 *
 * <p>{@code @ServiceConnection} makes Spring Boot point the DataSource at this container, and
 * Flyway then migrates it on startup, so tests exercise the same migrations as production.
 * Spring caches the application context across test classes, so one container is shared.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfig {

    // Keep in sync with <postgres.image> in pom.xml and docker-compose.yml.
    static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse("postgres:17-alpine");

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgres() {
        return new PostgreSQLContainer<>(POSTGRES_IMAGE);
    }
}
