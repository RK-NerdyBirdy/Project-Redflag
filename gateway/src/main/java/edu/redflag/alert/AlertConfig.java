package edu.redflag.alert;

import java.util.Arrays;
import java.util.List;

/**
 * Immutable configuration record for SMTP email alerting in Project Redflag.
 *
 * @param id          Unique configuration identifier
 * @param smtpHost    SMTP server hostname or IP address
 * @param smtpPort    SMTP port (typically 587 for TLS, 465 for SSL, or 25)
 * @param tls         Whether STARTTLS/TLS transport security is enabled
 * @param smtpUser    SMTP username or sending email address
 * @param smtpPass    SMTP password or app-specific authentication token
 * @param recipients  Comma-separated list of alert recipient email addresses
 * @param enabled     Whether email alerts are globally enabled
 */
public record AlertConfig(
    int id,
    String smtpHost,
    int smtpPort,
    boolean tls,
    String smtpUser,
    String smtpPass,
    String recipients,
    boolean enabled
) {
    /**
     * Parses and returns the list of trimmed, non-blank recipient email addresses.
     *
     * @return immutable list of recipient emails
     */
    public List<String> recipientList() {
        if (recipients == null || recipients.isBlank()) {
            return List.of();
        }
        return Arrays.stream(recipients.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .toList();
    }
}
