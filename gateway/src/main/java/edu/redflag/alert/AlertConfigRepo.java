package edu.redflag.alert;

import edu.redflag.persist.Db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Data access repository for managing SMTP alerting configuration and transmission logs
 * using the Project Redflag database connection pool.
 */
public final class AlertConfigRepo {

    private AlertConfigRepo() {
        // Utility class
    }

    /**
     * Loads the current SMTP alert configuration from {@code email_alert_config} for id=1.
     * Returns a default disabled configuration if no record is found or upon database error.
     *
     * @return current {@link AlertConfig}
     */
    public static AlertConfig load() {
        final var sql = """
            SELECT id, smtp_host, smtp_port, smtp_tls, smtp_user, smtp_pass_enc, recipients, enabled
            FROM email_alert_config
            WHERE id = 1
            """;
        try (final Connection conn = Db.get();
             final PreparedStatement ps = conn.prepareStatement(sql);
             final ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                return new AlertConfig(
                    rs.getInt("id"),
                    rs.getString("smtp_host"),
                    rs.getInt("smtp_port"),
                    rs.getInt("smtp_tls") != 0,
                    rs.getString("smtp_user"),
                    rs.getString("smtp_pass_enc"),
                    rs.getString("recipients"),
                    rs.getInt("enabled") != 0
                );
            }
        } catch (SQLException e) {
            System.err.println("[AlertConfigRepo] Failed to load alert config from DB: " + e.getMessage());
        }
        return new AlertConfig(1, "smtp.gmail.com", 587, true, "", "", "", false);
    }

    /**
     * Saves or updates the SMTP alert configuration in {@code email_alert_config}.
     * Executes an UPSERT pattern (UPDATE if id=1 exists, otherwise INSERT).
     *
     * @param cfg the {@link AlertConfig} to persist
     */
    public static void save(final AlertConfig cfg) {
        final var checkSql = "SELECT COUNT(*) FROM email_alert_config WHERE id = 1";
        try (final Connection conn = Db.get();
             final PreparedStatement checkPs = conn.prepareStatement(checkSql);
             final ResultSet rs = checkPs.executeQuery()) {
            final boolean exists = rs.next() && rs.getInt(1) > 0;
            if (exists) {
                final var updateSql = """
                    UPDATE email_alert_config
                    SET smtp_host = ?, smtp_port = ?, smtp_tls = ?, smtp_user = ?, smtp_pass_enc = ?,
                        recipients = ?, enabled = ?, updated_at = CURRENT_TIMESTAMP
                    WHERE id = 1
                    """;
                try (final PreparedStatement updatePs = conn.prepareStatement(updateSql)) {
                    updatePs.setString(1, cfg.smtpHost());
                    updatePs.setInt(2, cfg.smtpPort());
                    updatePs.setInt(3, cfg.tls() ? 1 : 0);
                    updatePs.setString(4, cfg.smtpUser());
                    updatePs.setString(5, cfg.smtpPass());
                    updatePs.setString(6, cfg.recipients());
                    updatePs.setInt(7, cfg.enabled() ? 1 : 0);
                    updatePs.executeUpdate();
                }
            } else {
                final var insertSql = """
                    INSERT INTO email_alert_config (id, smtp_host, smtp_port, smtp_tls, smtp_user, smtp_pass_enc, recipients, enabled, updated_at)
                    VALUES (1, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                    """;
                try (final PreparedStatement insertPs = conn.prepareStatement(insertSql)) {
                    insertPs.setString(1, cfg.smtpHost());
                    insertPs.setInt(2, cfg.smtpPort());
                    insertPs.setInt(3, cfg.tls() ? 1 : 0);
                    insertPs.setString(4, cfg.smtpUser());
                    insertPs.setString(5, cfg.smtpPass());
                    insertPs.setString(6, cfg.recipients());
                    insertPs.setInt(7, cfg.enabled() ? 1 : 0);
                    insertPs.executeUpdate();
                }
            }
        } catch (SQLException e) {
            System.err.println("[AlertConfigRepo] Failed to save alert config to DB: " + e.getMessage());
        }
    }

    /**
     * Records an email alert dispatch attempt in {@code email_alert_log}.
     *
     * @param nodeId      Target IoT node ID
     * @param subject     Email subject line
     * @param bodyPreview Truncated or summary preview of the alert body
     * @param success     Whether the email dispatch succeeded
     * @param errorMsg    Failure diagnostic or error message (if any)
     */
    public static void logAlert(final String nodeId, final String subject, final String bodyPreview, final boolean success, final String errorMsg) {
        final var sql = """
            INSERT INTO email_alert_log (node_id, sent_at, subject, body_preview, success, error_msg)
            VALUES (?, CURRENT_TIMESTAMP, ?, ?, ?, ?)
            """;
        try (final Connection conn = Db.get();
             final PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, nodeId);
            ps.setString(2, subject);
            ps.setString(3, bodyPreview);
            ps.setInt(4, success ? 1 : 0);
            ps.setString(5, errorMsg != null ? errorMsg : "");
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[AlertConfigRepo] Failed to insert alert log into DB: " + e.getMessage());
        }
    }
}
