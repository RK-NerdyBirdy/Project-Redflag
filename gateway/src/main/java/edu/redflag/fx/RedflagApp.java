package edu.redflag.fx;

import edu.redflag.core.FeatureSchema;
import edu.redflag.core.GatewayPipeline;
import edu.redflag.event.MitigationController;
import edu.redflag.event.TelemetryObservable;
import edu.redflag.infer.HeuristicInferenceStrategy;
import edu.redflag.infer.NativeModel;
import edu.redflag.infer.XGBoostInferenceStrategy;
import edu.redflag.io.SerialReaderRunnable;
import edu.redflag.model.RawPacket;
import edu.redflag.persist.AuditWriter;
import edu.redflag.persist.Db;
import edu.redflag.web.EmbeddedWebServer;
import edu.redflag.web.TelemetryWebSocket;
import edu.redflag.core.DeviceRegistry;
import javafx.application.Application;
import javafx.concurrent.Task;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * Main application entry point for Project Redflag (Codename: Redflag).
 * Initializes the full gateway lifecycle: ML models, DB pool, Serial Link, Web Console, and JavaFX UI.
 */
public class RedflagApp extends Application {
    private SerialReaderRunnable serialReader;
    private GatewayPipeline pipeline;
    private AuditWriter auditWriter;
    private EmbeddedWebServer webServer;
    private XGBoostInferenceStrategy xgbStrategy;
    public static MitigationController GLOBAL_MITIGATION_CONTROLLER;

    @Override
    public void start(Stage primaryStage) throws Exception {
        FXMLLoader mainLoader = new FXMLLoader(getClass().getResource("/fxml/MainLayout.fxml"));
        Scene mainScene = new Scene(mainLoader.load(), 1280, 800);
        String cssPath = getClass().getResource("/css/redflag.css") != null
            ? getClass().getResource("/css/redflag.css").toExternalForm() : null;
        if (cssPath != null) {
            mainScene.getStylesheets().add(cssPath);
        }
        MainLayoutController mainController = mainLoader.getController();
        primaryStage.setTitle("Project Redflag - IoT Volumetric DDoS Edge Gateway");
        primaryStage.setScene(mainScene);
        primaryStage.centerOnScreen();
        primaryStage.show();

        startBackgroundTasks(mainController);
    }

    private void startBackgroundTasks(MainLayoutController mainController) {
        // Heavy background startup task off the JavaFX Application Thread
        Task<Void> bootstrapTask = new Task<>() {
            @Override
            protected Void call() throws Exception {
                System.out.println("=====================================================");
                System.out.println("  PROJECT REDFLAG — EDGE GATEWAY BOOTSTRAP");
                System.out.println("=====================================================");

                // 1. Locate schema and model artifacts
                Path schemaPath = resolveFile("models/feature_names.json", "feature_columns.json", "models/feature_columns.json");
                Path modelPath  = resolveFile("models/redflag_xgb.json", "redflag_model.json", "models/redflag_model.json");

                System.out.println("[BOOT] Loading Feature Schema from " + schemaPath);
                FeatureSchema schema = new FeatureSchema(schemaPath);
                System.out.println("[BOOT] Feature vector dimensions: " + schema.size);

                System.out.println("[BOOT] Loading XGBoost Model from " + modelPath);
                NativeModel nativeModel = NativeModel.load(modelPath);
                xgbStrategy = new XGBoostInferenceStrategy(nativeModel, schema.size);

                System.out.println("[BOOT] Running 5,000-cycle JIT warm-up protocol...");
                long warmUpStart = System.nanoTime();
                xgbStrategy.warmUp(5000);
                long warmUpMs = (System.nanoTime() - warmUpStart) / 1_000_000L;
                System.out.println("[BOOT] Warm-up complete in " + warmUpMs + " ms.");

                // 2. Ingress queue and event bus
                BlockingQueue<RawPacket> ingressQueue = new ArrayBlockingQueue<>(4096);
                TelemetryObservable eventBus = new TelemetryObservable();

                // 3. Database persistence
                String dbUrl = System.getProperty("rf.db.url", System.getenv().getOrDefault("RF_DB_URL", "jdbc:sqlite:redflag.db"));
                String dbUser = System.getProperty("rf.db.user", System.getenv().getOrDefault("RF_DB_USER", null));
                String dbPass = System.getProperty("rf.db.pass", System.getenv().getOrDefault("RF_DB_PASS", null));
                System.out.println("[BOOT] Initializing Database: " + dbUrl);
                Db.init(dbUrl, dbUser, dbPass);

                auditWriter = new AuditWriter();

                // 4. No auto-start hardware link here, DevicesController will start it
                MitigationController mitigation = new MitigationController();
                GLOBAL_MITIGATION_CONTROLLER = mitigation; // we'll need to add this global

                // 5. Observer Registrations
                eventBus.subscribe(mitigation);
                eventBus.subscribe(auditWriter);
                eventBus.subscribe(new TelemetryWebSocket.Broadcaster());

                // 6. Build & start pipeline
                HeuristicInferenceStrategy fallback = new HeuristicInferenceStrategy(
                    schema.idxMeanDt[1], // W=20 mean
                    schema.idxStdDt[1]   // W=20 std
                );

                // Create a registry that will manage per-node sessions and observers
                DeviceRegistry deviceRegistry = new DeviceRegistry();
                // Build pipeline with the registry
                pipeline = new GatewayPipeline(ingressQueue, deviceRegistry, schema, xgbStrategy, fallback, eventBus, 2);
                pipeline.start();

                // 7. Start embedded web server
                int webPort = Integer.parseInt(System.getProperty("rf.web.port", "8080"));
                webServer = new EmbeddedWebServer(webPort, ingressQueue, deviceRegistry);
                try {
                    webServer.start();
                } catch (Exception e) {
                    System.err.println("[WEB] Warning: Embedded web server could not start on port " + webPort + ": " + e.getMessage());
                    e.printStackTrace();
                }

                System.out.println("[BOOT] Gateway Pipeline is ACTIVE and MONITORING.");
                javafx.application.Platform.runLater(() -> {
                    try {
                        mainController.loadViews();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                });
                return null;
            }
        };

        bootstrapTask.setOnFailed(e -> {
            Throwable ex = bootstrapTask.getException();
            System.err.println("[BOOT ERROR] Fatal startup fault: " + ex.getMessage());
            ex.printStackTrace();
        });

        Thread bootThread = new Thread(bootstrapTask, "gateway-bootstrap-thread");
        bootThread.setDaemon(true);
        bootThread.start();
    }

    private static Path resolveFile(String... candidatePaths) {
        for (String c : candidatePaths) {
            Path p = Path.of(c);
            if (Files.isReadable(p)) return p;
            Path parentP = Path.of("..", c);
            if (Files.isReadable(parentP)) return parentP;
        }
        return Path.of(candidatePaths[0]);
    }

    @Override
    public void stop() {
        System.out.println("\n[SHUTDOWN] Performing clean gateway teardown...");
        if (serialReader != null) serialReader.close();
        if (pipeline != null) pipeline.close();
        if (auditWriter != null) auditWriter.close();
        if (webServer != null) webServer.close();
        Db.close();
        System.out.println("[SHUTDOWN] Resources released cleanly. Goodbye.");
    }

    public static void main(String[] args) {
        launch(args);
    }
}
