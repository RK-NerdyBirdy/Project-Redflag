package edu.redflag.persist;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import edu.redflag.auth.PasswordUtil;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * High-performance database connection manager utilizing HikariCP.
 * Supports both PostgreSQL and SQLite (with automatic table schema provisioning and Flyway migrations).
 */
public final class Db {
    private static HikariDataSource dataSource;
    private static boolean isSqlite = false;

    private Db() {}

    /**
     * Initializes the connection pool and applies database migrations.
     *
     * @param jdbcUrl  JDBC connection string
     * @param username database user or null for SQLite
     * @param password database password or null for SQLite
     */
    public static synchronized void init(final String jdbcUrl, final String username, final String password) {
        if (dataSource != null) {
            return;
        }

        final var config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        if (username != null) config.setUsername(username);
        if (password != null) config.setPassword(password);

        isSqlite = jdbcUrl.startsWith("jdbc:sqlite:");

        if (isSqlite) {
            config.setMaximumPoolSize(8);
            config.setMinimumIdle(2);
            config.setConnectionTimeout(15_000);
            config.setConnectionInitSql("PRAGMA journal_mode=WAL; PRAGMA busy_timeout=5000; PRAGMA synchronous=NORMAL;");
        } else {
            config.setMaximumPoolSize(10);
            config.setMinimumIdle(2);
            config.setConnectionTimeout(10_000);
            config.addDataSourceProperty("reWriteBatchedInserts", "true");
        }
        config.setMaxLifetime(30 * 60_000L);

        dataSource = new HikariDataSource(config);

        // Automatically apply Flyway migrations on startup
        try {
            final var flyway = org.flywaydb.core.Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load();
            flyway.migrate();
            System.out.println("[DB] Flyway migrations applied successfully.");
            ensureUsersColumns();
            ensureIndexes();
            ensureDefaultAdmin();
        } catch (Exception e) {
            System.err.println("[DB] Flyway migration skipped or failed: " + e.getMessage());
            createSchemaIfAbsent(); // fallback
            ensureUsersColumns();
            ensureIndexes();
            ensureDefaultAdmin();
        }
    }

    private static void ensureIndexes() {
        try (final Connection conn = get(); final Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_telemetry_id ON telemetry_logs(id DESC)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_telemetry_node_seq ON telemetry_logs(node_id, seq)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_mitigation_id ON mitigation_events(id DESC)");
        } catch (SQLException e) {
            System.err.println("[DB] Index verification notice: " + e.getMessage());
        }
    }

    private static void ensureUsersColumns() {
        try (final Connection conn = get(); final Statement stmt = conn.createStatement()) {
            if (isSqlite) {
                try (final ResultSet rs = stmt.executeQuery("PRAGMA table_info(users)")) {
                    boolean hasCreatedAt = false;
                    while (rs.next()) {
                        if ("created_at".equalsIgnoreCase(rs.getString("name"))) {
                            hasCreatedAt = true;
                            break;
                        }
                    }
                    if (!hasCreatedAt) {
                        stmt.execute("ALTER TABLE users ADD COLUMN created_at TEXT DEFAULT (datetime('now'))");
                    }
                }
            } else {
                stmt.execute("ALTER TABLE users ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT now()");
            }
        } catch (SQLException e) {
            System.err.println("[DB] Note on users table schema: " + e.getMessage());
        }
    }

    private static void ensureDefaultAdmin() {
        try (final Connection conn = get()) {
            boolean hasAdmin = false;
            try (final PreparedStatement ps = conn.prepareStatement("SELECT id FROM users WHERE email = ?")) {
                ps.setString(1, "admin@redflag.local");
                try (final ResultSet rs = ps.executeQuery()) {
                    hasAdmin = rs.next();
                }
            }
            if (!hasAdmin) {
                final String defaultEmail = "admin@redflag.local";
                final String hashedPwd = PasswordUtil.hash("Admin@1234");
                final String defaultRole = "ADMIN";
                try (final PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO users (email, pwd_hash, role) VALUES (?, ?, ?)")) {
                    ps.setString(1, defaultEmail);
                    ps.setString(2, hashedPwd);
                    ps.setString(3, defaultRole);
                    ps.executeUpdate();
                    System.out.println("[DB] Initialized default admin user: " + defaultEmail);
                }
            }
        } catch (SQLException e) {
            System.err.println("[DB] Error checking/seeding default admin user: " + e.getMessage());
        }
    }

    private static void createSchemaIfAbsent() {
        try (final Connection conn = get(); final Statement stmt = conn.createStatement()) {
            if (isSqlite) {
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS users (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        email TEXT UNIQUE NOT NULL,
                        pwd_hash TEXT NOT NULL,
                        role TEXT NOT NULL,
                        created_at TEXT DEFAULT (datetime('now'))
                    );
                """);
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS email_alert_config (
                        id INTEGER PRIMARY KEY,
                        smtp_host TEXT NOT NULL,
                        smtp_port INTEGER NOT NULL,
                        smtp_tls INTEGER NOT NULL DEFAULT 1,
                        smtp_user TEXT,
                        smtp_pass_enc TEXT,
                        recipients TEXT,
                        enabled INTEGER NOT NULL DEFAULT 0,
                        updated_at TEXT DEFAULT CURRENT_TIMESTAMP
                    );
                """);
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS email_alert_log (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        node_id TEXT,
                        sent_at TEXT DEFAULT CURRENT_TIMESTAMP,
                        subject TEXT,
                        body_preview TEXT,
                        success INTEGER NOT NULL,
                        error_msg TEXT
                    );
                """);
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
                    CREATE TABLE IF NOT EXISTS users (
                        id SERIAL PRIMARY KEY,
                        email TEXT UNIQUE NOT NULL,
                        pwd_hash TEXT NOT NULL,
                        role TEXT NOT NULL,
                        created_at TIMESTAMPTZ NOT NULL DEFAULT now()
                    );
                """);
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS email_alert_config (
                        id INT PRIMARY KEY,
                        smtp_host TEXT NOT NULL,
                        smtp_port INT NOT NULL,
                        smtp_tls INT NOT NULL DEFAULT 1,
                        smtp_user TEXT,
                        smtp_pass_enc TEXT,
                        recipients TEXT,
                        enabled INT NOT NULL DEFAULT 0,
                        updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
                    );
                """);
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS email_alert_log (
                        id BIGSERIAL PRIMARY KEY,
                        node_id TEXT,
                        sent_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                        subject TEXT,
                        body_preview TEXT,
                        success INT NOT NULL,
                        error_msg TEXT
                    );
                """);
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

    /**
     * Obtains an active connection from the pool.
     *
     * @return active {@link Connection}
     * @throws SQLException if database pool is uninitialized or exhausted
     */
    public static Connection get() throws SQLException {
        if (dataSource == null) {
            throw new SQLException("Database connection pool is not initialized. Call Db.init() first.");
        }
        return dataSource.getConnection();
    }

    /**
     * Checks if the active pool is configured for SQLite mode.
     *
     * @return {@code true} if running against SQLite; {@code false} otherwise
     */
    public static boolean isSqliteMode() {
        return isSqlite;
    }

    /**
     * Closes the connection pool and releases database resources.
     */
    public static synchronized void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            dataSource = null;
        }
    }
}
