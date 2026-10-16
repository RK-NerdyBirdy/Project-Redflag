package edu.redflag.fx;

import edu.redflag.auth.SessionContext;
import edu.redflag.auth.User;
import javafx.animation.AnimationTimer;
import javafx.animation.FadeTransition;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Main application window layout controller managing view swapping,
 * header user credentials, live status bars, audio/visual attack banners, and session logout.
 */
public class MainLayoutController {

    @FXML private Label headerUserLabel;
    @FXML private Label statusBarUptime;
    @FXML private Label statusBarNodes;
    @FXML private Label statusBarAttacks;

    @FXML private StackPane contentArea;
    @FXML private Pane alertPane;
    @FXML private Label alertLabel;

    @FXML private Button btnDashboard;
    @FXML private Button btnDevices;
    @FXML private Button btnReports;
    @FXML private Button btnUsers;
    @FXML private Button btnSettings;

    private static volatile MainLayoutController instance;
    private static final AtomicInteger TOTAL_ATTACK_COUNT = new AtomicInteger(0);

    private final Map<String, Parent> views = new HashMap<>();
    private long startTime;

    /**
     * Retrieves the active MainLayoutController singleton instance.
     *
     * @return current instance, or null if uninitialized
     */
    public static MainLayoutController getInstance() {
        return instance;
    }

    /**
     * Loads all sub-views into memory, displays the default dashboard,
     * and updates the header user info.
     *
     * @throws IOException if any FXML resource cannot be loaded
     */
    public void loadViews() {
        loadViewSafe("Dashboard", "/fxml/Overview.fxml");
        loadViewSafe("Devices", "/fxml/Devices.fxml");
        loadViewSafe("Reports", "/fxml/Reports.fxml");
        loadViewSafe("Users", "/fxml/Users.fxml");
        loadViewSafe("Settings", "/fxml/Settings.fxml");

        showDashboard();

        Platform.runLater(() -> {
            if (headerUserLabel != null) {
                headerUserLabel.setText(SessionContext.email() + " [" + SessionContext.current().map(User::role).orElse("") + "]");
            }
        });
    }

    private void loadViewSafe(final String name, final String fxmlPath) {
        try {
            final var res = getClass().getResource(fxmlPath);
            if (res != null) {
                views.put(name, FXMLLoader.load(res));
            } else {
                System.err.println("[MAIN] FXML resource not found: " + fxmlPath);
            }
        } catch (Exception e) {
            System.err.println("[MAIN] Error loading view [" + name + "] from " + fxmlPath + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Initializes controller instance references, records startup timestamp,
     * and activates the real-time status bar uptime timer.
     */
    @FXML
    public void initialize() {
        instance = this;
        startTime = System.currentTimeMillis();

        final var uptimeTimer = new AnimationTimer() {
            private long lastUpdate = 0;

            @Override
            public void handle(final long now) {
                if (now - lastUpdate >= 1_000_000_000L) {
                    lastUpdate = now;
                    final var elapsedSec = (System.currentTimeMillis() - startTime) / 1000L;
                    final var hh = elapsedSec / 3600L;
                    final var mm = (elapsedSec % 3600L) / 60L;
                    final var ss = elapsedSec % 60L;
                    if (statusBarUptime != null) {
                        statusBarUptime.setText(String.format("Uptime: %02d:%02d:%02d", hh, mm, ss));
                    }
                    if (statusBarNodes != null) {
                        statusBarNodes.setText("Nodes: " + OverviewController.getActiveNodesCount());
                    }
                    if (statusBarAttacks != null) {
                        final long attacks = Math.max(TOTAL_ATTACK_COUNT.get(), OverviewController.getTotalAttacksCount());
                        statusBarAttacks.setText("Attacks: " + attacks);
                        if (attacks > 0) {
                            statusBarAttacks.setStyle("-fx-text-fill: #ff4444; -fx-font-weight: bold; -fx-font-size: 12px;");
                        } else {
                            statusBarAttacks.setStyle("-fx-text-fill: #888888; -fx-font-size: 12px;");
                        }
                    }
                }
            }
        };
        uptimeTimer.start();
    }

    @FXML private void showDashboard() { switchView("Dashboard"); }
    @FXML private void showDevices() { switchView("Devices"); }
    @FXML private void showReports() { switchView("Reports"); }
    @FXML private void showUsers() { switchView("Users"); }
    @FXML private void showSettings() { switchView("Settings"); }

    private void switchView(final String name) {
        final var view = views.get(name);
        if (view != null && contentArea != null) {
            contentArea.getChildren().clear();
            contentArea.getChildren().add(view);
        }
    }

    /**
     * Broadcasts an attack notification banner across the UI, triggers acoustic alarms,
     * and increments the status bar attack metric.
     *
     * @param node  originating IoT device node ID
     * @param score detected volumetric attack probability score
     */
    private static Timeline flashingBorderTimeline;

    /**
     * Broadcasts an attack notification banner across the UI, triggers acoustic alarms and voice,
     * flashes the window border in red, and increments the status bar attack metric.
     *
     * @param node  originating IoT device node ID
     * @param score detected volumetric attack probability score
     */
    public static void triggerAttackBanner(final String node, final double score) {
        AlarmAudio.playAttackAlert();
        VoiceAlert.speakAttack(node);
        final var attacks = TOTAL_ATTACK_COUNT.incrementAndGet();

        if (instance != null) {
            Platform.runLater(() -> {
                if (instance.statusBarAttacks != null) {
                    instance.statusBarAttacks.setText("Attacks: " + attacks);
                }
                if (instance.alertLabel != null) {
                    instance.alertLabel.setText(String.format("VOLUMETRIC FLOOD :: NODE [%s] :: SEV [%.2f]", node, score));
                }
                if (instance.alertPane != null) {
                    instance.alertPane.setVisible(true);

                    final var ft = new FadeTransition(Duration.millis(300), instance.alertPane);
                    ft.setFromValue(0.3);
                    ft.setToValue(1.0);
                    ft.setCycleCount(10);
                    ft.setAutoReverse(true);
                    ft.play();
                }

                // Dramatic flashing border around content area
                if (instance.contentArea != null) {
                    if (flashingBorderTimeline != null) {
                        flashingBorderTimeline.stop();
                    }
                    final String brightBorder = "-fx-border-color: #ff0000; -fx-border-width: 6px; -fx-effect: dropshadow(gaussian, #ff0000, 24, 0.9, 0, 0);";
                    final String dimBorder    = "-fx-border-color: rgba(255, 0, 0, 0.15); -fx-border-width: 6px;";

                    flashingBorderTimeline = new Timeline(
                        new KeyFrame(Duration.ZERO, e -> instance.contentArea.setStyle(brightBorder)),
                        new KeyFrame(Duration.millis(250), e -> instance.contentArea.setStyle(dimBorder)),
                        new KeyFrame(Duration.millis(500))
                    );
                    flashingBorderTimeline.setCycleCount(8); // 4 seconds of rhythmic pulsing
                    flashingBorderTimeline.setOnFinished(e -> {
                        if (instance != null) {
                            if (instance.contentArea != null) instance.contentArea.setStyle("");
                            if (instance.alertPane != null) instance.alertPane.setVisible(false);
                        }
                    });
                    flashingBorderTimeline.play();
                }
            });
        }
    }

    /**
     * Updates the global bottom status bar with the current active node count and total attack count.
     *
     * @param activeNodes number of currently responsive IoT edge nodes
     * @param attackCount total number of mitigated attack events
     */
    public static void updateStatusBar(final int activeNodes, final int attackCount) {
        if (instance != null) {
            Platform.runLater(() -> {
                if (instance.statusBarNodes != null) {
                    instance.statusBarNodes.setText("Nodes: " + activeNodes);
                }
                if (instance.statusBarAttacks != null) {
                    instance.statusBarAttacks.setText("Attacks: " + attackCount);
                }
            });
        }
    }

    @FXML
    private void handleLogout() {
        System.out.println("[AUTH] Logging out operator session...");
        SessionContext.logout();
        if (contentArea != null && contentArea.getScene() != null && contentArea.getScene().getWindow() != null) {
            final var currentStage = (Stage) contentArea.getScene().getWindow();
            currentStage.close();
        }
        RedflagApp.showLogin(new Stage());
    }
}
