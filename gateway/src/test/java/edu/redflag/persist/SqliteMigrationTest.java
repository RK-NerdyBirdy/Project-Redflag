package edu.redflag.persist;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class SqliteMigrationTest {

    @AfterEach
    void tearDown() {
        Db.close();
    }

    @Test
    void testExistingRedflagDbMigrationToV4() throws Exception {
        File dbFile = new File("redflag.db").exists() ? new File("redflag.db") : new File("../redflag.db");
        if (dbFile.exists()) {
            Db.init("jdbc:sqlite:" + dbFile.getAbsolutePath(), null, null);
            try (Connection conn = Db.get(); Statement stmt = conn.createStatement()) {
                try (ResultSet rs = stmt.executeQuery("SELECT count(*) FROM email_alert_config")) {
                    assertTrue(rs.next());
                    assertTrue(rs.getInt(1) >= 1, "Default email alert config should exist");
                }
            }
        }
    }

    @Test
    void testFreshSqliteFlywayMigrationAndInit() throws Exception {
        new File("test_fresh_redflag.db").delete();
        try {
            Db.init("jdbc:sqlite:test_fresh_redflag.db", null, null);
            try (Connection conn = Db.get(); Statement stmt = conn.createStatement()) {
                try (ResultSet rs = stmt.executeQuery("SELECT count(*) FROM users")) {
                    assertTrue(rs.next());
                    assertTrue(rs.getInt(1) >= 1, "Default admin user should be seeded");
                }

                try (ResultSet rs = stmt.executeQuery("SELECT count(*) FROM email_alert_config")) {
                    assertTrue(rs.next());
                    assertTrue(rs.getInt(1) >= 1, "Default email alert config should be seeded");
                }
            }
        } finally {
            Db.close();
            new File("test_fresh_redflag.db").delete();
        }
    }
}
