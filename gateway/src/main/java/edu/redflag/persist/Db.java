package edu.redflag.persist;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * High-performance database connection manager utilizing HikariCP.
 * Supports both PostgreSQL and SQLite (with automatic table schema provisioning).
 */
public final class Db {
    private static HikariDataSource dataSource;
    private static boolean isSqlite = false;

    private Db() {}

    public static synchronized void init(String jdbcUrl, String username, String password) {
        if (dataSource != null) {
            return;
        }

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        if (username != null) config.setUsername(username);
        if (password != null) config.setPassword(password);

        isSqlite = jdbcUrl.startsWith("jdbc:sqlite:");

        config.setMaximumPoolSize(isSqlite ? 1 : 4);
        config.setMinimumIdle(1);
        config.setConnectionTimeout(5_000);
        config.setMaxLifetime(30 * 60_000L);

        if (!isSqlite) {
            config.addDataSourceProperty("reWriteBatchedInserts", "true");
        }

        dataSource = new HikariDataSource(config);

        // Automatically apply Flyway migrations on startup
        try {
            org.flywaydb.core.Flyway flyway = org.flywaydb.core.Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load();
            flyway.migrate();
            System.out.println("[DB] Flyway migrations applied successfully.");
        } catch (Exception e) {
            System.err.println("[DB] Flyway migration skipped or failed: " + e.getMessage());
            createSchemaIfAbsent(); // fallback
        }
    }

    private static void createSchemaIfAbsent() {
        try (Connection conn = get(); Statement stmt = conn.createStatement()) {
            if (isSqlite) {
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS nodes (
                        node_id     TEXT PRIMARY KEY,
                        first_seen  TEXT NOT NULL DEFAULT (datetime('now')),
                        last_seen   TEXT NOT NULL DEFAULT (datetime('now')),
                        fw_version  TEXT
                    );
                """);
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS telemetry_logs (
                        id            INTEGER PRIMARY KEY AUTOINCREMENT,
                        node_id       TEXT NOT NULL,
                        seq           INTEGER NOT NULL,
                        stime_us      INTEGER NOT NULL,
                        delta_t_ms    REAL,
                        dt_std_20     REAL,
                        bytes         INTEGER NOT NULL,
                        proto         TEXT,
                        state         TEXT,
                        flgs          TEXT,
                        score         REAL NOT NULL,
                        is_attack     INTEGER NOT NULL,
                        infer_us      INTEGER NOT NULL,
                        http_status   INTEGER NOT NULL,
                        logged_at     TEXT NOT NULL DEFAULT (datetime('now'))
                    );
                """);
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS mitigation_events (
                        id               INTEGER PRIMARY KEY AUTOINCREMENT,
                        node_id          TEXT NOT NULL,
                        started_at       TEXT NOT NULL DEFAULT (datetime('now')),
                        ended_at         TEXT,
                        vector           TEXT NOT NULL,
                        peak_score       REAL NOT NULL,
                        packets_blocked  INTEGER NOT NULL DEFAULT 0,
                        action           TEXT NOT NULL DEFAULT 'HTTP_403'
                    );
                """);
            } else {
                // PostgreSQL dialect
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS nodes (
                        node_id     TEXT PRIMARY KEY,
                        first_seen  TIMESTAMPTZ NOT NULL DEFAULT now(),
                        last_seen   TIMESTAMPTZ NOT NULL DEFAULT now(),
                        fw_version  TEXT
                    );
                """);
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS telemetry_logs (
                        id            BIGSERIAL PRIMARY KEY,
                        node_id       TEXT NOT NULL REFERENCES nodes(node_id),
                        seq           BIGINT NOT NULL,
                        stime_us      BIGINT NOT NULL,
                        delta_t_ms    DOUBLE PRECISION,
                        dt_std_20     DOUBLE PRECISION,
                        bytes         INTEGER NOT NULL,
                        proto         TEXT,
                        state         TEXT,
                        flgs          TEXT,
                        score         REAL NOT NULL,
                        is_attack     BOOLEAN NOT NULL,
                        infer_us      INTEGER NOT NULL,
                        http_status   SMALLINT NOT NULL,
                        logged_at     TIMESTAMPTZ NOT NULL DEFAULT now()
                    );
                """);
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS mitigation_events (
                        id               BIGSERIAL PRIMARY KEY,
                        node_id          TEXT NOT NULL REFERENCES nodes(node_id),
                        started_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
                        ended_at         TIMESTAMPTZ,
                        vector           TEXT NOT NULL,
                        peak_score       REAL NOT NULL,
                        packets_blocked  BIGINT NOT NULL DEFAULT 0,
                        action           TEXT NOT NULL DEFAULT 'HTTP_403'
                    );
                """);
            }
        } catch (SQLException e) {
            System.err.println("[DB] Warning: Schema initialization notice: " + e.getMessage());
        }
    }

    public static Connection get() throws SQLException {
        if (dataSource == null) {
            throw new SQLException("Database connection pool is not initialized. Call Db.init() first.");
        }
        return dataSource.getConnection();
    }

    public static boolean isSqliteMode() {
        return isSqlite;
    }

    public static synchronized void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            dataSource = null;
        }
    }
}
