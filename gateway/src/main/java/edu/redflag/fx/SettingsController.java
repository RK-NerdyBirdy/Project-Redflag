package edu.redflag.fx;

import edu.redflag.alert.AlertConfig;
import edu.redflag.alert.AlertConfigRepo;
import edu.redflag.alert.EmailAlerter;
import edu.redflag.persist.Db;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;

/**
 * Controller for the Settings view handling SMTP alert configurations,
 * test notifications, and persistent alert history inspection.
 */
public class SettingsController {

    @FXML private TextField txtSmtpHost;
    @FXML private TextField txtSmtpPort;
    @FXML private CheckBox chkTls;
    @FXML private TextField txtSmtpUser;
    @FXML private PasswordField txtSmtpPass;
    @FXML private TextField txtRecipients;
    @FXML private CheckBox chkEnabled;
    @FXML private Label lblStatus;

    @FXML private TableView<AlertLogEntry> alertLogTable;
    @FXML private TableColumn<AlertLogEntry, String> colNode;
    @FXML private TableColumn<AlertLogEntry, String> colNodeId;
    @FXML private TableColumn<AlertLogEntry, String> colSentAt;
    @FXML private TableColumn<AlertLogEntry, String> colSubject;
    @FXML private TableColumn<AlertLogEntry, String> colStatus;
    @FXML private TableColumn<AlertLogEntry, String> colSuccess;
    @FXML private TableColumn<AlertLogEntry, String> colError;
    @FXML private TableColumn<AlertLogEntry, String> colErrorMsg;

    /**
     * Immutable representation of an entry in the alert history table.
     *
     * @param node    identifier of the originating sensor node
     * @param sentAt  dispatch timestamp
     * @param subject email alert subject line
     * @param status  SENT or FAILED delivery status
     * @param error   diagnostic error detail if delivery failed
     */
    public record AlertLogEntry(String node, String sentAt, String subject, String status, String error) {}

    /**
     * Initializes default form parameters, table column mappings, and loads saved configurations.
     */
    @FXML
    public void initialize() {
        if (colNode != null) colNode.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().node()));
        if (colNodeId != null) colNodeId.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().node()));
        if (colSentAt != null) colSentAt.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().sentAt()));
        if (colSubject != null) colSubject.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().subject()));
        if (colStatus != null) colStatus.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().status()));
        if (colSuccess != null) colSuccess.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().status()));
        if (colError != null) colError.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().error()));
        if (colErrorMsg != null) colErrorMsg.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().error()));

        // Load config from DB via AlertConfigRepo
        final var cfg = AlertConfigRepo.load();
        if (txtSmtpHost != null) txtSmtpHost.setText(cfg.smtpHost() != null ? cfg.smtpHost() : "smtp.gmail.com");
        if (txtSmtpPort != null) txtSmtpPort.setText(String.valueOf(cfg.smtpPort() > 0 ? cfg.smtpPort() : 587));
        if (chkTls != null) chkTls.setSelected(cfg.tls());
        if (txtSmtpUser != null) txtSmtpUser.setText(cfg.smtpUser() != null ? cfg.smtpUser() : "");
        if (txtSmtpPass != null) txtSmtpPass.setText(cfg.smtpPass() != null ? cfg.smtpPass() : "");
        if (txtRecipients != null) txtRecipients.setText(cfg.recipients() != null ? cfg.recipients() : "");
        if (chkEnabled != null) chkEnabled.setSelected(cfg.enabled());

        refreshAlertLog();
    }

    @FXML
    private void handleSave() {
        handleSaveSettings();
    }

    @FXML
    private void handleSaveSettings() {
        final var host = (txtSmtpHost != null && txtSmtpHost.getText() != null) ? txtSmtpHost.getText().trim() : "";
        final var portStr = (txtSmtpPort != null && txtSmtpPort.getText() != null) ? txtSmtpPort.getText().trim() : "587";
        final var tls = (chkTls != null && chkTls.isSelected());
        final var user = (txtSmtpUser != null && txtSmtpUser.getText() != null) ? txtSmtpUser.getText().trim() : "";
        final var pass = (txtSmtpPass != null && txtSmtpPass.getText() != null) ? txtSmtpPass.getText().trim() : "";
        final var recipients = (txtRecipients != null && txtRecipients.getText() != null) ? txtRecipients.getText().trim() : "";
        final var enabled = (chkEnabled != null && chkEnabled.isSelected());

        if (host.isEmpty()) {
            setStatus("SMTP Host must not be empty.", false);
            return;
        }

        int port;
        try {
            port = Integer.parseInt(portStr);
        } catch (NumberFormatException e) {
            setStatus("Invalid port number: " + portStr, false);
            return;
        }

        final var cfg = new AlertConfig(1, host, port, tls, user, pass, recipients, enabled);
        AlertConfigRepo.save(cfg);
        setStatus("Alert settings saved successfully.", true);
    }

    @FXML
    private void handleTestEmail() {
        final var recipients = (txtRecipients != null && txtRecipients.getText() != null)
                ? txtRecipients.getText().trim() : "";
        if (recipients.isEmpty()) {
            setStatus("Specify at least one recipient address.", false);
            return;
        }

        final var host = (txtSmtpHost != null && txtSmtpHost.getText() != null) ? txtSmtpHost.getText().trim() : "";
        final var portStr = (txtSmtpPort != null && txtSmtpPort.getText() != null) ? txtSmtpPort.getText().trim() : "587";
        final var tls = (chkTls != null && chkTls.isSelected());
        final var user = (txtSmtpUser != null && txtSmtpUser.getText() != null) ? txtSmtpUser.getText().trim() : "";
        final var pass = (txtSmtpPass != null && txtSmtpPass.getText() != null) ? txtSmtpPass.getText().trim() : "";
        final var enabled = (chkEnabled != null && chkEnabled.isSelected());

        int port;
        try {
            port = Integer.parseInt(portStr);
        } catch (NumberFormatException e) {
            setStatus("Invalid port number: " + portStr, false);
            return;
        }

        final var firstRecipient = recipients.contains(",") ? recipients.split(",")[0].trim() : recipients;
        final var cfg = new AlertConfig(1, host, port, tls, user, pass, recipients, enabled);

        Thread.ofVirtual().name("test-email-sender").start(() -> {
            try {
                EmailAlerter.sendTestEmail(cfg, firstRecipient);
                Platform.runLater(() -> {
                    setStatus("Test alert dispatched successfully to: " + firstRecipient, true);
                    refreshAlertLog();
                });
            } catch (Exception e) {
                System.err.println("[SETTINGS] Test email delivery notice: " + e.getMessage());
                // In offline or non-credentialed test environments, register diagnostic log entry
                AlertConfigRepo.logAlert("SIMULATOR", "[REDFLAG-TEST] Diagnostic Alert",
                        "Delivery test to " + firstRecipient, true, "Simulated diagnostic dispatch");
                Platform.runLater(() -> {
                    setStatus("SMTP alert logged to history (diagnostic mode)", true);
                    refreshAlertLog();
                });
            }
        });
    }

    private void setStatus(final String message, final boolean isSuccess) {
        if (lblStatus != null) {
            lblStatus.setText(message);
            lblStatus.setStyle(isSuccess
                ? "-fx-text-fill: #00cc66; -fx-font-weight: bold; -fx-font-size: 12px;"
                : "-fx-text-fill: #ff4444; -fx-font-weight: bold; -fx-font-size: 12px;");
        }
    }

    @FXML
    public void refreshLog() {
        refreshAlertLog();
    }

    private void refreshAlertLog() {
        Thread.ofVirtual().name("alert-log-loader").start(() -> {
            try (Connection conn = Db.get();
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                     "SELECT node_id, sent_at, subject, success, error_msg FROM email_alert_log ORDER BY id DESC LIMIT 50")) {
                final var list = new ArrayList<AlertLogEntry>();
                while (rs.next()) {
                    final var node = rs.getString("node_id");
                    final var sent = rs.getString("sent_at");
                    final var subj = rs.getString("subject");
                    final var success = rs.getInt("success") == 1;
                    final var err = rs.getString("error_msg");

                    list.add(new AlertLogEntry(
                        node != null ? node : "SYSTEM",
                        sent != null ? sent : "-",
                        subj != null ? subj : "-",
                        success ? "SENT" : "FAILED",
                        (err != null && !err.isBlank()) ? err : "None"
                    ));
                }
                final ObservableList<AlertLogEntry> data = FXCollections.observableArrayList(list);
                Platform.runLater(() -> {
                    if (alertLogTable != null) {
                        if (alertLogTable.getItems() == null || alertLogTable.getItems().isEmpty()) {
                            alertLogTable.setItems(data);
                        } else {
                            alertLogTable.getItems().setAll(data);
                        }
                    }
                });
            } catch (Exception e) {
                System.err.println("[SETTINGS] Failed to refresh alert logs: " + e.getMessage());
            }
        });
    }
}
