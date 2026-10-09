package com.ardakeles.ledger.concurrency;

import com.ardakeles.ledger.TestcontainersConfig;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * A Postgres for benchmarking only, with real durability. The shared test container runs with
 * fsync=off, which makes COMMIT unrealistically cheap and would flatter whichever strategy holds locks
 * across the commit. Testcontainers' PostgreSQLContainer sets that flag by default, so the command is
 * overridden with a bare "postgres" to get stock settings. This is still a container on a developer
 * laptop's Docker VM, so absolute numbers mean little; the comparison between strategies is what counts.
 */
@TestConfiguration(proxyBeanMethods = false)
class BenchmarkContainerConfig {

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgres() {
        return new PostgreSQLContainer<>(TestcontainersConfig.POSTGRES_IMAGE).withCommand("postgres");
    }
}
