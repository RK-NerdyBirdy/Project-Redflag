package edu.redflag.fx;

import edu.redflag.auth.PasswordUtil;
import edu.redflag.auth.SessionContext;
import edu.redflag.auth.User;
import edu.redflag.persist.Db;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;

/**
 * Controller for the secure login gate view.
 * Performs database-backed user authentication with asynchronous verification,
 * loading indicator handling, and session initialization.
 */
public class LoginController {

    @FXML private TextField emailField;
    @FXML private PasswordField passwordField;
    @FXML private Label errorLabel;
    @FXML private ProgressIndicator loginProgress;
    @FXML private Button loginBtn;

    private Runnable onSuccess;

    /**
     * Registers a callback action to execute upon successful authentication.
     *
     * @param onSuccess runnable invoked after successful login
     */
    public void setOnSuccess(final Runnable onSuccess) {
        this.onSuccess = onSuccess;
    }

    /**
     * Initializes keyboard shortcuts and UI event handlers.
     */
    @FXML
    public void initialize() {
        if (loginProgress != null) {
            loginProgress.setVisible(false);
        }
        if (passwordField != null) {
            passwordField.setOnKeyPressed(event -> {
                if (event.getCode() == KeyCode.ENTER) {
                    handleLogin();
                }
            });
        }
        if (emailField != null) {
            emailField.setOnKeyPressed(event -> {
                if (event.getCode() == KeyCode.ENTER) {
                    passwordField.requestFocus();
                }
            });
        }
    }

    @FXML
    private void handleLogin() {
        final var email = (emailField != null && emailField.getText() != null)
                ? emailField.getText().trim() : "";
        final var pwd = (passwordField != null && passwordField.getText() != null)
                ? passwordField.getText().trim() : "";

        if (email.isEmpty() || pwd.isEmpty()) {
            if (errorLabel != null) {
                errorLabel.setText("Please enter both email and password");
            }
            return;
        }

        if (loginProgress != null) {
            loginProgress.setVisible(true);
        }
        if (loginBtn != null) {
            loginBtn.setDisable(true);
        }
        if (errorLabel != null) {
            errorLabel.setText("");
        }

        final var authThread = new Thread(() -> {
            try (final var conn = Db.get();
                 final var ps = conn.prepareStatement("SELECT id, email, pwd_hash, role FROM users WHERE email = ?")) {
                ps.setString(1, email);
                final var rs = ps.executeQuery();
                if (rs.next() && PasswordUtil.verify(pwd, rs.getString("pwd_hash"))) {
                    final var u = new User(
                        rs.getInt("id"),
                        rs.getString("email"),
                        rs.getString("pwd_hash"),
                        rs.getString("role")
                    );
                    Platform.runLater(() -> {
                        if (loginProgress != null) loginProgress.setVisible(false);
                        if (loginBtn != null) loginBtn.setDisable(false);
                        SessionContext.login(u);
                        if (onSuccess != null) {
                            onSuccess.run();
                        }
                    });
                } else {
                    Platform.runLater(() -> {
                        if (loginProgress != null) loginProgress.setVisible(false);
                        if (loginBtn != null) loginBtn.setDisable(false);
                        if (errorLabel != null) {
                            errorLabel.setText("Invalid email or password");
                        }
                    });
                }
            } catch (Exception ex) {
                System.err.println("[AUTH] Database authentication error: " + ex.getMessage());
                Platform.runLater(() -> {
                    if (loginProgress != null) loginProgress.setVisible(false);
                    if (loginBtn != null) loginBtn.setDisable(false);
                    if (errorLabel != null) {
                        errorLabel.setText("Database error: " + ex.getMessage());
                    }
                });
            }
        }, "auth-worker");
        authThread.setDaemon(true);
        authThread.start();
    }
}
