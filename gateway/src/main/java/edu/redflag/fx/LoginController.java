package edu.redflag.fx;

import edu.redflag.auth.User;
import edu.redflag.auth.UserSession;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.stage.Stage;

public class LoginController {

    @FXML private TextField emailField;
    @FXML private PasswordField passwordField;
    @FXML private Label errorLabel;

    private Runnable onSuccess;

    public void setOnSuccess(Runnable onSuccess) {
        this.onSuccess = onSuccess;
    }

    @FXML
    private void handleLogin(ActionEvent event) {
        String email = emailField.getText();
        String pwd = passwordField.getText();

        if ("admin@example.com".equals(email) && "password".equals(pwd)) {
            User u = new User(1, email, "hash", "ADMIN");
            String token = UserSession.generateToken(u);
            // In a real app we might store the token, but for now just proceed
            if (onSuccess != null) {
                onSuccess.run();
            }
        } else {
            errorLabel.setText("Invalid credentials");
        }
    }
}
