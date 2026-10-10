package edu.redflag.persist;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.flywaydb.core.Flyway;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.testcontainers.DockerClientFactory;
import org.junit.jupiter.api.Assumptions;

public class AuditWriterTest {

    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
        "postgres:15-alpine"
    );

    @BeforeAll
    static void beforeAll() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker not available, skipping tests");
        postgres.start();

        // Run Flyway migrations
        Flyway flyway = Flyway.configure()
            .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
            .locations("classpath:db/migration")
            .load();
        flyway.migrate();

        Db.init(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    @AfterAll
    static void afterAll() {
        Db.close();
        postgres.stop();
    }

    @Test
    void testDatabaseMigrationAndConnection() throws Exception {
        try (Connection conn = Db.get(); Statement stmt = conn.createStatement()) {
            ResultSet rs = stmt.executeQuery("SELECT count(*) FROM users");
            assertTrue(rs.next());
        }
    }
}
