package edu.redflag.fx;

import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.layout.StackPane;
import javafx.application.Platform;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class MainLayoutController {
    @FXML private StackPane contentArea;

    private final Map<String, Parent> views = new HashMap<>();

    public void loadViews() throws IOException {
        views.put("Dashboard", FXMLLoader.load(getClass().getResource("/fxml/Overview.fxml")));
        views.put("Devices", FXMLLoader.load(getClass().getResource("/fxml/Devices.fxml")));
        views.put("Reports", FXMLLoader.load(getClass().getResource("/fxml/Reports.fxml")));
        views.put("Users", FXMLLoader.load(getClass().getResource("/fxml/Users.fxml")));

        showDashboard();
    }

    @FXML private void showDashboard() { switchView("Dashboard"); }
    @FXML private void showDevices() { switchView("Devices"); }
    @FXML private void showReports() { switchView("Reports"); }
    @FXML private void showUsers() { switchView("Users"); }

    private void switchView(String name) {
        Parent view = views.get(name);
        if (view != null) {
            contentArea.getChildren().clear();
            contentArea.getChildren().add(view);
        }
    }

    private static MainLayoutController instance;
    @FXML private StackPane alertPane;
    @FXML private javafx.scene.control.Label alertLabel;

    public void initialize() {
        instance = this;
    }

    public static void triggerAttackBanner(String node, double score) {
        if (instance != null) {
            java.awt.Toolkit.getDefaultToolkit().beep();
            Platform.runLater(() -> {
                instance.alertLabel.setText(String.format("VOLUMETRIC FLOOD :: NODE [%s] :: SEV [%.2f]", node, score));
                instance.alertPane.setVisible(true);
                instance.contentArea.setStyle("-fx-border-color: #ff0000; -fx-border-width: 8;");
                
                javafx.animation.FadeTransition ft = new javafx.animation.FadeTransition(javafx.util.Duration.millis(300), instance.alertPane);
                ft.setFromValue(0.3);
                ft.setToValue(1.0);
                ft.setCycleCount(10);
                ft.setAutoReverse(true);
                ft.play();

                // Auto hide after 5 seconds
                new Thread(() -> {
                    try { Thread.sleep(5000); } catch (InterruptedException ignored) {}
                    Platform.runLater(() -> {
                        instance.alertPane.setVisible(false);
                        instance.contentArea.setStyle("");
                    });
                }).start();
            });
        }
    }

    @FXML private void handleLogout() {
        System.out.println("Logging out...");
        Platform.exit();
        System.exit(0);
    }
}
