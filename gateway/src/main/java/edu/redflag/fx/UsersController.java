package edu.redflag.fx;

import edu.redflag.persist.Db;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.*;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

public class UsersController {
    @FXML private TableView<UserEntry> usersTable;
    @FXML private TextField txtEmail;
    @FXML private PasswordField txtPassword;
    @FXML private ComboBox<String> comboRole;

    public void initialize() {
        comboRole.setItems(FXCollections.observableArrayList("ADMIN", "VIEWER"));
        comboRole.getSelectionModel().select("VIEWER");
        refreshUsers();
    }

    private void refreshUsers() {
        ObservableList<UserEntry> data = FXCollections.observableArrayList();
        try (Connection conn = Db.get(); Statement stmt = conn.createStatement()) {
            ResultSet rs = stmt.executeQuery("SELECT id, email, role FROM users ORDER BY id");
            while (rs.next()) {
                data.add(new UserEntry(
                    rs.getInt("id"),
                    rs.getString("email"),
                    rs.getString("role")
                ));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        usersTable.setItems(data);
    }

    @FXML
    private void handleAddUser() {
        String email = txtEmail.getText().trim();
        String pass = txtPassword.getText().trim();
        String role = comboRole.getValue();

        if (email.isEmpty() || pass.isEmpty() || role == null) {
            showAlert(Alert.AlertType.ERROR, "All fields are required.");
            return;
        }

        try (Connection conn = Db.get(); 
             PreparedStatement ps = conn.prepareStatement("INSERT INTO users(email, pwd_hash, role) VALUES (?, ?, ?)")) {
            ps.setString(1, email);
            ps.setString(2, pass); // Note: Should be hashed in production
            ps.setString(3, role);
            ps.executeUpdate();
            
            txtEmail.clear();
            txtPassword.clear();
            refreshUsers();
        } catch (Exception e) {
            showAlert(Alert.AlertType.ERROR, "Failed to add user: " + e.getMessage());
        }
    }

    @FXML
    private void handleDeleteUser() {
        UserEntry selected = usersTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            showAlert(Alert.AlertType.WARNING, "Select a user to delete.");
            return;
        }

        try (Connection conn = Db.get(); 
             PreparedStatement ps = conn.prepareStatement("DELETE FROM users WHERE id = ?")) {
            ps.setInt(1, selected.getId());
            ps.executeUpdate();
            refreshUsers();
        } catch (Exception e) {
            showAlert(Alert.AlertType.ERROR, "Failed to delete user: " + e.getMessage());
        }
    }

    private void showAlert(Alert.AlertType type, String message) {
        Alert alert = new Alert(type, message);
        alert.showAndWait();
    }

    public static class UserEntry {
        private final int id;
        private final String email;
        private final String role;

        public UserEntry(int id, String email, String role) {
            this.id = id;
            this.email = email;
            this.role = role;
        }

        public int getId() { return id; }
        public String getEmail() { return email; }
        public String getRole() { return role; }
    }
}
