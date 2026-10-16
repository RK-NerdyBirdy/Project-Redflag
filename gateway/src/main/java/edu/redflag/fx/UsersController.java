package edu.redflag.fx;

import edu.redflag.auth.PasswordUtil;
import edu.redflag.auth.SessionContext;
import edu.redflag.persist.Db;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.*;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * JavaFX Controller for the User Management administrative console.
 * Supports user listing, creation with BCrypt hashing, deletion, and password reset.
 */
public class UsersController {

    @FXML private TableView<UserEntry> usersTable;
    @FXML private TextField txtEmail;
    @FXML private PasswordField txtPassword;
    @FXML private ComboBox<String> comboRole;
    @FXML private TableColumn<UserEntry, Integer> colId;
    @FXML private TableColumn<UserEntry, String> colEmail;
    @FXML private TableColumn<UserEntry, String> colRole;

    /**
     * Initializes the UI components, role combobox choices, badge cell styling, and loads users.
     */
    public void initialize() {
        comboRole.setItems(FXCollections.observableArrayList("ADMIN", "VIEWER"));
        comboRole.getSelectionModel().select("VIEWER");

        if (colRole != null) {
            colRole.setCellFactory(col -> new TableCell<>() {
                @Override
                protected void updateItem(String role, boolean empty) {
                    super.updateItem(role, empty);
                    if (empty || role == null) {
                        setText(null);
                        setGraphic(null);
                        setStyle("");
                    } else {
                        setText(role);
                        if ("ADMIN".equalsIgnoreCase(role)) {
                            setStyle("-fx-text-fill: #ff4d4f; -fx-font-weight: bold; -fx-alignment: CENTER; " +
                                     "-fx-background-color: rgba(255, 77, 79, 0.18); -fx-background-radius: 4; -fx-padding: 3 8;");
                        } else {
                            setStyle("-fx-text-fill: #52c41a; -fx-font-weight: bold; -fx-alignment: CENTER; " +
                                     "-fx-background-color: rgba(82, 196, 26, 0.18); -fx-background-radius: 4; -fx-padding: 3 8;");
                        }
                    }
                }
            });
        }

        refreshUsers();
    }

    /**
     * Queries all user accounts from the database and refreshes the table display.
     */
    public void refreshUsers() {
        Thread.ofVirtual().name("users-loader").start(() -> {
            final ObservableList<UserEntry> data = FXCollections.observableArrayList();
            try (final Connection conn = Db.get();
                 final Statement stmt = conn.createStatement();
                 final ResultSet rs = stmt.executeQuery("SELECT id, email, role FROM users ORDER BY id")) {
                while (rs.next()) {
                    data.add(new UserEntry(
                        rs.getInt("id"),
                        rs.getString("email"),
                        rs.getString("role")
                    ));
                }
            } catch (SQLException e) {
                System.err.println("[UsersController] Failed to query users: " + e.getMessage());
            }
            javafx.application.Platform.runLater(() -> {
                if (usersTable != null) {
                    if (usersTable.getItems() == null || usersTable.getItems().isEmpty()) {
                        usersTable.setItems(data);
                    } else {
                        usersTable.getItems().setAll(data);
                    }
                }
            });
        });
    }

    @FXML
    private void handleAddUser() {
        if (!SessionContext.isAdmin()) {
            showAlert(Alert.AlertType.ERROR, "Access denied: Admin role required.");
            return;
        }

        final var email = txtEmail.getText().trim();
        final var pass = txtPassword.getText().trim();
        final var role = comboRole.getValue();

        if (email.isEmpty() || pass.isEmpty() || role == null) {
            showAlert(Alert.AlertType.ERROR, "All fields are required.");
            return;
        }

        final var hashed = PasswordUtil.hash(pass);

        try (final Connection conn = Db.get(); 
             final PreparedStatement ps = conn.prepareStatement("INSERT INTO users(email, pwd_hash, role) VALUES (?, ?, ?)")) {
            ps.setString(1, email);
            ps.setString(2, hashed);
            ps.setString(3, role);
            ps.executeUpdate();
            
            txtEmail.clear();
            txtPassword.clear();
            refreshUsers();
            showAlert(Alert.AlertType.INFORMATION, "User added successfully.");
        } catch (Exception e) {
            System.err.println("[UsersController] Failed to add user: " + e.getMessage());
            showAlert(Alert.AlertType.ERROR, "Failed to add user: " + e.getMessage());
        }
    }

    @FXML
    private void handleDeleteUser() {
        if (!SessionContext.isAdmin()) {
            showAlert(Alert.AlertType.ERROR, "Access denied: Admin role required.");
            return;
        }

        final var selected = usersTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            showAlert(Alert.AlertType.WARNING, "Select a user to delete.");
            return;
        }

        try (final Connection conn = Db.get(); 
             final PreparedStatement ps = conn.prepareStatement("DELETE FROM users WHERE id = ?")) {
            ps.setInt(1, selected.getId());
            ps.executeUpdate();
            refreshUsers();
            showAlert(Alert.AlertType.INFORMATION, "User deleted successfully.");
        } catch (Exception e) {
            System.err.println("[UsersController] Failed to delete user: " + e.getMessage());
            showAlert(Alert.AlertType.ERROR, "Failed to delete user: " + e.getMessage());
        }
    }

    @FXML
    private void handleResetPassword() {
        if (!SessionContext.isAdmin()) {
            showAlert(Alert.AlertType.ERROR, "Access denied: Admin role required.");
            return;
        }

        final var selected = usersTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            showAlert(Alert.AlertType.WARNING, "Select a user to reset password.");
            return;
        }

        final var dialog = new TextInputDialog();
        dialog.setTitle("Reset Password");
        dialog.setHeaderText("Reset password for: " + selected.getEmail());
        dialog.setContentText("Enter new password:");

        final var opt = dialog.showAndWait();
        if (opt.isPresent()) {
            final var newPass = opt.get().trim();
            if (newPass.isEmpty()) {
                showAlert(Alert.AlertType.ERROR, "Password cannot be empty.");
                return;
            }

            final var hashed = PasswordUtil.hash(newPass);
            try (final Connection conn = Db.get();
                 final PreparedStatement ps = conn.prepareStatement("UPDATE users SET pwd_hash = ? WHERE id = ?")) {
                ps.setString(1, hashed);
                ps.setInt(2, selected.getId());
                final int updated = ps.executeUpdate();
                if (updated > 0) {
                    showAlert(Alert.AlertType.INFORMATION, "Password reset successfully for " + selected.getEmail());
                }
            } catch (SQLException e) {
                System.err.println("[UsersController] Failed to reset password: " + e.getMessage());
                showAlert(Alert.AlertType.ERROR, "Failed to reset password: " + e.getMessage());
            }
        }
    }

    private void showAlert(final Alert.AlertType type, final String message) {
        final var alert = new Alert(type, message);
        alert.showAndWait();
    }

    /**
     * Data model entry representing a user record row in the JavaFX TableView.
     */
    public static class UserEntry {
        private final int id;
        private final String email;
        private final String role;

        public UserEntry(final int id, final String email, final String role) {
            this.id = id;
            this.email = email;
            this.role = role;
        }

        public int getId() { return id; }
        public String getEmail() { return email; }
        public String getRole() { return role; }
    }
}
