package edu.redflag.fx;

import edu.redflag.auth.SessionContext;
import edu.redflag.core.DeviceRegistry;
import edu.redflag.core.FeatureSchema;
import edu.redflag.core.GatewayPipeline;
import edu.redflag.event.MitigationController;
import edu.redflag.event.TelemetryObservable;
import edu.redflag.infer.HeuristicInferenceStrategy;
import edu.redflag.infer.NativeModel;
import edu.redflag.infer.XGBoostInferenceStrategy;
import edu.redflag.io.SerialReaderRunnable;
import edu.redflag.io.TcpListenerServer;
import edu.redflag.model.RawPacket;
import edu.redflag.persist.AuditWriter;
import edu.redflag.persist.Db;
import edu.redflag.web.EmbeddedWebServer;
import edu.redflag.web.TelemetryWebSocket;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * Main application entry point for Project Redflag (IoT Volumetric DDoS Edge Gateway).
 * Handles authentication gating, stage transitions, ML model initialization,
 * TCP/Serial multi-device ingestion, and graceful system teardown.
 */
public class RedflagApp extends Application {
    private static RedflagApp instance;
    private SerialReaderRunnable serialReader;
    private TcpListenerServer tcpServer;
    private GatewayPipeline pipeline;
    private AuditWriter auditWriter;
    private EmbeddedWebServer webServer;
    private XGBoostInferenceStrategy xgbStrategy;

    /**
     * Globally accessible mitigation controller instance.
     */
    public static MitigationController GLOBAL_MITIGATION_CONTROLLER;

    /**
     * Globally accessible TCP listener server instance.
     */
    public static TcpListenerServer GLOBAL_TCP_SERVER;

    /**
     * Returns the singleton instance of the running Redflag application.
     *
     * @return the application instance
     */
    public static RedflagApp getInstance() {
        return instance;
    }

    @Override
    public void start(final Stage primaryStage) throws Exception {
        instance = this;

        // Ensure database pool is initialized before any authentication attempts
        final var dbUrl = System.getProperty("rf.db.url", System.getenv().getOrDefault("RF_DB_URL", "jdbc:sqlite:redflag.db"));
        final var dbUser = System.getProperty("rf.db.user", System.getenv().getOrDefault("RF_DB_USER", null));
        final var dbPass = System.getProperty("rf.db.pass", System.getenv().getOrDefault("RF_DB_PASS", null));
        System.out.println("[BOOT] Initializing Database connection pool: " + dbUrl);
        Db.init(dbUrl, dbUser, dbPass);

        // Display login window as the primary stage
        showLogin(primaryStage);
    }

    /**
     * Configures and displays the secure login gate window.
     *
     * @param loginStage the stage in which to present the login view
     */
    public static void showLogin(final Stage loginStage) {
        try {
            final var loginLoader = new FXMLLoader(RedflagApp.class.getResource("/fxml/login.fxml"));
            final var loginScene = new Scene(loginLoader.load(), 800, 500);
            final var cssResource = RedflagApp.class.getResource("/css/redflag.css");
            if (cssResource != null) {
                loginScene.getStylesheets().add(cssResource.toExternalForm());
            }

            final LoginController loginController = loginLoader.getController();
            loginStage.setTitle("Project Redflag — Secure Login");
            loginStage.setScene(loginScene);
            loginStage.setResizable(false);
            loginStage.centerOnScreen();

            loginController.setOnSuccess(() -> {
                loginStage.close();

                try {
                    final var mainStage = new Stage();
                    final var mainLoader = new FXMLLoader(RedflagApp.class.getResource("/fxml/MainLayout.fxml"));
                    final var mainScene = new Scene(mainLoader.load(), 1280, 800);

                    if (cssResource != null) {
                        mainScene.getStylesheets().add(cssResource.toExternalForm());
                    }

                    mainStage.setTitle("Project Redflag - IoT Volumetric DDoS Edge Gateway");
                    mainStage.setScene(mainScene);
                    mainStage.centerOnScreen();
                    mainStage.show();

                    final MainLayoutController mainController = mainLoader.getController();
                    try {
                        mainController.loadViews();
                    } catch (final Throwable e) {
                        System.err.println("[APP] Notice while loading views: " + e.getMessage());
                        e.printStackTrace();
                    }

                    if (instance != null) {
                        instance.startBackgroundTasks(mainController);
                    }
                } catch (final Throwable e) {
                    System.err.println("[APP] Failed to load main layout: " + e.getMessage());
                    e.printStackTrace();
                }
            });

            loginStage.show();
        } catch (final IOException e) {
            System.err.println("[APP] Failed to load login FXML: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Initializes heavy ML inference pipelines, web services, TCP listener, and telemetry buses
     * asynchronously in the background.
     *
     * @param mainController the active MainLayoutController instance
     */
    public void startBackgroundTasks(final MainLayoutController mainController) {
        if (pipeline != null) {
            System.out.println("[BOOT] Telemetry pipeline already active. Reusing running background tasks.");
            return;
        }

        final var bootstrapTask = new Task<Void>() {
            @Override
            protected Void call() throws Exception {
                System.out.println("=====================================================");
                System.out.println("  PROJECT REDFLAG — EDGE GATEWAY BOOTSTRAP");
                System.out.println("=====================================================");

                // 1. Locate schema and model artifacts
                final var schemaPath = resolveFile("models/feature_names.json", "feature_columns.json", "models/feature_columns.json");
                final var modelPath = resolveFile("models/redflag_xgb.json", "redflag_model.json", "models/redflag_model.json");

                System.out.println("[BOOT] Loading Feature Schema from " + schemaPath);
                final var schema = new FeatureSchema(schemaPath);
                System.out.println("[BOOT] Feature vector dimensions: " + schema.size);

                System.out.println("[BOOT] Loading XGBoost Model from " + modelPath);
                final var nativeModel = NativeModel.load(modelPath);
                xgbStrategy = new XGBoostInferenceStrategy(nativeModel, schema.size);

                System.out.println("[BOOT] Running 5,000-cycle JIT warm-up protocol...");
                final var warmUpStart = System.nanoTime();
                xgbStrategy.warmUp(5000);
                final var warmUpMs = (System.nanoTime() - warmUpStart) / 1_000_000L;
                System.out.println("[BOOT] Warm-up complete in " + warmUpMs + " ms.");

                // 2. Ingress queue and event bus
                final BlockingQueue<RawPacket> ingressQueue = new ArrayBlockingQueue<>(4096);
                final var eventBus = new TelemetryObservable();

                // 3. Database persistence audit writer
                auditWriter = new AuditWriter();

                // 4. Mitigation Controller
                final var mitigation = new MitigationController();
                GLOBAL_MITIGATION_CONTROLLER = mitigation;

                // 5. Observer Registrations
                eventBus.subscribe(mitigation);
                eventBus.subscribe(auditWriter);
                eventBus.subscribe(new TelemetryWebSocket.Broadcaster());
                if (DevicesController.getInstance() != null) {
                    eventBus.subscribe(DevicesController.getInstance());
                }
                eventBus.subscribe(frame -> {
                    final var packet = frame.packet();
                    final var nodeId = (packet != null && packet.nodeId() != null) ? packet.nodeId() : "unknown";
                    final var proto = (packet != null && packet.proto() != null) ? packet.proto() : "OTHER";
                    final var inferUs = frame.inferNanos() > 0 ? (frame.inferNanos() / 1000L) : 0L;
                    OverviewController.recordPacket(nodeId, frame.attack(), frame.score(), inferUs);
                    OverviewController.recordProtocol(proto);
                });

                // 6. Build & start pipeline
                final var fallback = new HeuristicInferenceStrategy(
                    schema.idxMeanDt[1], // W=20 mean
                    schema.idxStdDt[1]   // W=20 std
                );

                final var deviceRegistry = new DeviceRegistry();
                pipeline = new GatewayPipeline(ingressQueue, deviceRegistry, schema, xgbStrategy, fallback, eventBus, 2);
                pipeline.start();

                // 7. Start TCP Listener Server for WiFi/ESP32 edge nodes
                final var tcpQueue = new ArrayBlockingQueue<RawPacket>(10000);
                tcpServer = new TcpListenerServer(tcpQueue);
                GLOBAL_TCP_SERVER = tcpServer;
                final var tcpThread = new Thread(tcpServer, "tcp-listener");
                tcpThread.setDaemon(false);
                tcpThread.start();

                // Wire the tcpQueue directly into GatewayPipeline
                pipeline.attachIngressQueue(tcpQueue);

                // 8. Start embedded web server
                final var webPort = Integer.parseInt(System.getProperty("rf.web.port", "8080"));
                webServer = new EmbeddedWebServer(webPort, ingressQueue, deviceRegistry);
                try {
                    webServer.start();
                } catch (final Exception e) {
                    System.err.println("[WEB] Warning: Embedded web server could not start on port " + webPort + ": " + e.getMessage());
                    e.printStackTrace();
                }

                System.out.println("[BOOT] Gateway Pipeline is ACTIVE and MONITORING.");
                return null;
            }
        };

        bootstrapTask.setOnFailed(e -> {
            final var ex = bootstrapTask.getException();
            System.err.println("[BOOT ERROR] Fatal startup fault: " + ex.getMessage());
            ex.printStackTrace();
        });

        final var bootThread = new Thread(bootstrapTask, "gateway-bootstrap-thread");
        bootThread.setDaemon(true);
        bootThread.start();
    }

    private static Path resolveFile(final String... candidatePaths) {
        for (final var c : candidatePaths) {
            final var p = Path.of(c);
            if (Files.isReadable(p)) return p;
            final var parentP = Path.of("..", c);
            if (Files.isReadable(parentP)) return parentP;
        }
        return Path.of(candidatePaths[0]);
    }

    @Override
    public void stop() {
        System.out.println("\n[SHUTDOWN] Performing clean gateway teardown...");
        if (tcpServer != null) tcpServer.shutdown();
        if (serialReader != null) serialReader.close();
        if (pipeline != null) pipeline.close();
        if (auditWriter != null) auditWriter.close();
        if (webServer != null) webServer.close();
        Db.close();
        System.out.println("[SHUTDOWN] Resources released cleanly. Goodbye.");
    }

    /**
     * Standard JVM entry point.
     *
     * @param args command-line arguments
     */
    public static void main(final String[] args) {
        launch(args);
    }
}
