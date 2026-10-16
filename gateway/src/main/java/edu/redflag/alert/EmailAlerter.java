package edu.redflag.alert;

import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Service dispatching automated email notifications for volumetric DDoS attack detections
 * and SMTP connectivity diagnostics in Project Redflag.
 */
public final class EmailAlerter {

    private static final ConcurrentHashMap<String, AtomicLong> lastSentPerNode = new ConcurrentHashMap<>();
    private static final long COOLDOWN_MS = 300_000L; // 5-minute per-node alert cooldown

    private EmailAlerter() {
        // Utility service
    }

    /**
     * Evaluates cooldown thresholds and asynchronously sends an attack alert email if enabled.
     *
     * @param nodeId Unique IoT edge node identifier
     * @param score  Inference threat probability score [0.0, 1.0]
     * @param vector Name of the detected attack vector
     */
    public static void sendAttackAlert(final String nodeId, final double score, final String vector) {
        final long now = System.currentTimeMillis();
        final var lastSent = lastSentPerNode.computeIfAbsent(nodeId, k -> new AtomicLong(0L));
        final long prev = lastSent.get();

        if (now - prev < COOLDOWN_MS) {
            return; // Cooldown period has not expired for this node
        }

        if (!lastSent.compareAndSet(prev, now)) {
            return; // Another thread already claimed the rate limit slot
        }

        final var alertThread = new Thread(() -> {
            final var cfg = AlertConfigRepo.load();
            if (!cfg.enabled()) {
                return;
            }

            final List<String> recipients = cfg.recipientList();
            if (recipients.isEmpty()) {
                return;
            }

            final var subject = String.format("[REDFLAG ALERT] Volumetric Attack Detected on Node: %s", nodeId);
            final var body = buildAttackAlertHtml(nodeId, score, vector);
            final var preview = String.format("Node: %s | Threat: %.1f%% | Vector: %s | Action: HTTP 403",
                    nodeId, score * 100.0, vector);

            try {
                sendMimeMessage(cfg, recipients, subject, body);
                AlertConfigRepo.logAlert(nodeId, subject, preview, true, null);
                System.out.println("[EmailAlerter] Attack alert successfully dispatched for node: " + nodeId);
            } catch (Exception e) {
                System.err.println("[EmailAlerter] Failed to dispatch attack alert email: " + e.getMessage());
                AlertConfigRepo.logAlert(nodeId, subject, preview, false, e.getMessage());
            }
        }, "redflag-email-alerter-" + nodeId);

        alertThread.setDaemon(true);
        alertThread.start();
    }

    /**
     * Synchronously sends a diagnostic test message to verify SMTP server credentials and connectivity.
     *
     * @param cfg SMTP configuration settings
     * @param to  Recipient email address
     * @throws Exception if transport or authentication fails
     */
    public static void sendTestEmail(final AlertConfig cfg, final String to) throws Exception {
        if (to == null || to.isBlank()) {
            throw new IllegalArgumentException("Recipient email address cannot be empty.");
        }

        final var subject = "[REDFLAG] SMTP Diagnostic Test Message";
        final var body = """
            <!DOCTYPE html>
            <html>
            <body style="font-family: Arial, sans-serif; background-color: #0b0f19; color: #ffffff; padding: 20px;">
              <div style="background-color: #141824; border: 2px solid #8b0000; border-radius: 8px; padding: 24px; max-width: 600px; margin: auto;">
                <h2 style="color: #ff3333; margin-top: 0; border-bottom: 1px solid #330000; padding-bottom: 10px;">REDFLAG IoT Gateway — SMTP Test</h2>
                <p>This is a test notification confirming that the Project Redflag SMTP email alerting channel is configured properly and operational.</p>
                <p><strong>Configured Host:</strong> %s:%d</p>
                <p><strong>Security:</strong> %s</p>
                <p><strong>Timestamp:</strong> %s</p>
              </div>
            </body>
            </html>
            """.formatted(cfg.smtpHost(), cfg.smtpPort(), cfg.tls() ? "STARTTLS" : "Plaintext", Instant.now().toString());

        sendMimeMessage(cfg, List.of(to), subject, body);
        AlertConfigRepo.logAlert("GATEWAY-TEST", subject, "Diagnostic SMTP test message sent to " + to, true, null);
    }

    private static void sendMimeMessage(final AlertConfig cfg, final List<String> recipients, final String subject, final String htmlBody) throws Exception {
        final var props = new Properties();
        props.put("mail.smtp.host", cfg.smtpHost());
        props.put("mail.smtp.port", String.valueOf(cfg.smtpPort()));
        if (cfg.tls()) {
            props.put("mail.smtp.starttls.enable", "true");
            props.put("mail.smtp.auth", "true");
        }

        final var session = Session.getInstance(props, new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(cfg.smtpUser(), cfg.smtpPass());
            }
        });

        final Message message = new MimeMessage(session);
        final String from = (cfg.smtpUser() != null && !cfg.smtpUser().isBlank()) ? cfg.smtpUser() : "redflag-alerts@redflag.local";
        message.setFrom(new InternetAddress(from));

        for (final var r : recipients) {
            message.addRecipient(Message.RecipientType.TO, new InternetAddress(r));
        }

        message.setSubject(subject);
        message.setContent(htmlBody, "text/html; charset=utf-8");
        Transport.send(message);
    }

    private static String buildAttackAlertHtml(final String nodeId, final double score, final String vector) {
        final var scorePercent = String.format("%.1f%%", score * 100.0);
        final var timestamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now());

        return """
            <!DOCTYPE html>
            <html>
            <head>
              <meta charset="utf-8">
              <style>
                body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif; background-color: #0b0f19; color: #f0f4f8; margin: 0; padding: 24px; }
                .card { background-color: #141824; border: 2px solid #8b0000; border-radius: 8px; max-width: 600px; margin: 0 auto; overflow: hidden; box-shadow: 0 4px 12px rgba(0,0,0,0.5); }
                .header { background-color: #550000; color: #ffffff; padding: 20px; text-align: center; }
                .header h1 { margin: 0; font-size: 24px; letter-spacing: 2px; }
                .header p { margin: 6px 0 0 0; font-size: 14px; opacity: 0.9; }
                .content { padding: 24px; }
                .detail-row { display: flex; justify-content: space-between; padding: 12px 0; border-bottom: 1px solid #222b40; }
                .detail-label { color: #8f9bb3; font-weight: bold; }
                .detail-value { color: #ffffff; font-family: monospace; font-size: 15px; }
                .badge-danger { background-color: #e02424; color: #ffffff; padding: 4px 8px; border-radius: 4px; font-weight: bold; }
                .footer { background-color: #0d111d; padding: 12px 24px; text-align: center; font-size: 12px; color: #6272a4; }
              </style>
            </head>
            <body>
              <div class="card">
                <div class="header">
                  <h1>REDFLAG SECURITY ALERT</h1>
                  <p>IoT Volumetric DDoS Edge Gateway Detection</p>
                </div>
                <div class="content">
                  <p style="font-size: 16px; margin-top: 0;">An active attack vector has breached threshold limits and is currently undergoing automated edge mitigation.</p>
                  <div class="detail-row">
                    <span class="detail-label">Target Node ID:</span>
                    <span class="detail-value"><strong>%s</strong></span>
                  </div>
                  <div class="detail-row">
                    <span class="detail-label">Threat Confidence:</span>
                    <span class="detail-value"><span class="badge-danger">%s</span></span>
                  </div>
                  <div class="detail-row">
                    <span class="detail-label">Attack Vector:</span>
                    <span class="detail-value">%s</span>
                  </div>
                  <div class="detail-row">
                    <span class="detail-label">Detection Timestamp:</span>
                    <span class="detail-value">%s</span>
                  </div>
                  <div class="detail-row">
                    <span class="detail-label">Automated Mitigation:</span>
                    <span class="detail-value" style="color: #ff5555; font-weight: bold;">HTTP 403 (BLOCKED)</span>
                  </div>
                </div>
                <div class="footer">
                  REDFLAG Edge Protection Engine &bull; Automated Telemetry Notification
                </div>
              </div>
            </body>
            </html>
            """.formatted(nodeId, scorePercent, vector, timestamp);
    }
}
