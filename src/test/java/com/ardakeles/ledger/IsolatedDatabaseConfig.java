package com.ardakeles.ledger;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.regex.Pattern;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * A separate database inside the shared Postgres container, for tests of whole-database jobs (reconciliation).
 * Other suites that assert something about the WHOLE database get their own database the same way, via
 * {@link #create} and {@link #connectionTo} (see the demo's {@code DemoDatabaseConfig}).
 *
 * <p>Such a job reads EVERYTHING, so it is only meaningful against data nobody else has dirtied. Other tests
 * (notably the raw-SQL schema tests, which set balances with no matching entries) legitimately leave rows
 * that reconciliation would flag, and sharing a database made the suite pass or fail depending on test
 * order. A database is a hard boundary: tables, sequences and advisory locks are all per-database. Use this
 * INSTEAD of {@link TestcontainersConfig}, not alongside it.
 */
@TestConfiguration(proxyBeanMethods = false)
public class IsolatedDatabaseConfig {

    static final String DATABASE = "reconciliation";

    static {
        create(DATABASE);
    }

    @Bean
    DynamicPropertyRegistrar isolatedPostgresConnection() {
        return connectionTo(DATABASE);
    }

    /** Creates {@code database} in the shared container. Call once per JVM, from a static initializer. */
    public static void create(String database) {
        PostgreSQLContainer<?> postgres = TestcontainersConfig.POSTGRES; // class init starts the shared container
        try (Connection admin = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement statement = admin.createStatement()) {
            statement.execute("CREATE DATABASE " + database);
        } catch (SQLException e) {
            throw new IllegalStateException("could not create the isolated test database " + database, e);
        }
    }

    /** Points a Spring context at {@code database} in the shared container. */
    public static DynamicPropertyRegistrar connectionTo(String database) {
        PostgreSQLContainer<?> postgres = TestcontainersConfig.POSTGRES;
        String url = postgres.getJdbcUrl().replaceFirst("/" + Pattern.quote(postgres.getDatabaseName()) + "(\\?|$)", "/" + database + "$1");
        return registry -> {
            registry.add("spring.datasource.url", () -> url);
            registry.add("spring.datasource.username", postgres::getUsername);
            registry.add("spring.datasource.password", postgres::getPassword);
        };
    }
}
