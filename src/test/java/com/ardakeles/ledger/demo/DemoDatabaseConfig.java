package com.ardakeles.ledger.demo;

import com.ardakeles.ledger.IsolatedDatabaseConfig;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;

/**
 * The demo's own database. DemoTest asserts that the sum over every USD account is exactly zero, a claim about
 * the WHOLE database, so it must not share one with tests that legitimately leave unbalanced rows behind (the
 * raw-SQL schema tests seed balances with no entries). Sharing made the result depend on test execution order,
 * which differs between macOS and the Linux CI runner. Use this INSTEAD of TestcontainersConfig.
 */
@TestConfiguration(proxyBeanMethods = false)
class DemoDatabaseConfig {

    static final String DATABASE = "demo";

    static {
        IsolatedDatabaseConfig.create(DATABASE);
    }

    @Bean
    DynamicPropertyRegistrar demoPostgresConnection() {
        return IsolatedDatabaseConfig.connectionTo(DATABASE);
    }
}
