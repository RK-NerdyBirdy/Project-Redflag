package edu.redflag.fx;

import edu.redflag.core.DeviceRegistry;
import edu.redflag.core.GatewayPipeline;
import edu.redflag.event.TelemetryObservable;
import edu.redflag.event.TelemetryObserver;
import edu.redflag.io.SerialReaderRunnable;
import edu.redflag.io.TcpListenerServer;
import edu.redflag.model.RawPacket;
import edu.redflag.model.TelemetryFrame;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.ToggleButton;
import javafx.util.Duration;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Controller for the Devices and Ingestion view.
 * Manages USB Serial ports (Arduino Uno), live TCP/WiFi connected nodes (ESP32),
 * and live per-device telemetry visualization tabs.
 */
public class DevicesController implements TelemetryObserver {

    @FXML private TabPane deviceTabs;
    @FXML private Label statusBadge;
    @FXML private ToggleButton mitigationToggle;
    @FXML private ToggleButton simulationToggle;
    @FXML private ComboBox<String> comPortCombo;
    @FXML private ListView<String> tcpDevicesList;
    @FXML private Label tcpServerStatus;

    private AtomicBoolean mitigationFlag;
    private final AtomicBoolean simRunning = new AtomicBoolean(false);
    private Thread simThread;

    private final ConcurrentHashMap<String, DeviceTabController> tabControllers = new ConcurrentHashMap<>();
    private final Set<String> pendingTabs = ConcurrentHashMap.newKeySet();

    private SerialReaderRunnable activeReader;
    private Timeline tcpRefreshTimeline;

    private static volatile DevicesController instance;

    public static DevicesController getInstance() {
        return instance;
    }

    public void bindMitigationFlag(final AtomicBoolean flag) {
        this.mitigationFlag = flag;
    }

    @FXML
    private void initialize() {
        instance = this;
        GatewayPipeline.subscribeGlobal(this);

        if (comPortCombo != null) {
            comPortCombo.setEditable(true);
            comPortCombo.setVisibleRowCount(10);
            comPortCombo.setOnShowing(e -> refreshPorts());
            refreshPorts();
        }

        final TelemetryObservable bus = GatewayPipeline.getGlobalEventBus();
        if (bus != null) {
            bus.subscribe(this);
        }

        final GatewayPipeline p = GatewayPipeline.getInstance();
        if (p != null) {
            mitigationFlag = p.mitigationFlag();
        }

        // Proactively open default tab for UNO-01 so graphs are immediately visible
        Platform.runLater(() -> openOrSelectTabForNode("UNO-01"));

        if (mitigationToggle != null) {
            mitigationToggle.selectedProperty().addListener((obs, oldVal, newVal) -> {
                if (mitigationFlag != null) {
                    mitigationFlag.set(newVal);
                }
                mitigationToggle.setText("MITIGATION: " + (newVal ? "ON" : "OFF"));
            });
        }

        if (simulationToggle != null) {
            simulationToggle.selectedProperty().addListener((obs, oldVal, active) -> {
                simulationToggle.setText("SIMULATOR: " + (active ? "ON" : "OFF"));
                if (active) {
                    startInternalSimulation();
                } else {
                    stopInternalSimulation();
                }
            });
        }

        // Setup TCP devices list interaction: click on item to switch to or open its telemetry tab
        if (tcpDevicesList != null) {
            tcpDevicesList.setOnMouseClicked(event -> {
                final String selected = tcpDevicesList.getSelectionModel().getSelectedItem();
                if (selected != null && !selected.isBlank()) {
                    final String nodeId = selected.split(" ")[0].trim();
                    openOrSelectTabForNode(nodeId);
                }
            });
        }

        // Periodic refresh (every 2 seconds) to sync TCP connected nodes from DeviceRegistry
        tcpRefreshTimeline = new Timeline(
            new KeyFrame(Duration.seconds(2), e -> refreshTcpStatus())
        );
        tcpRefreshTimeline.setCycleCount(Timeline.INDEFINITE);
        tcpRefreshTimeline.play();
        refreshTcpStatus();
    }

    /**
     * Periodic timer task synchronizing the TCP server status badge and connected device list.
     */
    private void refreshTcpStatus() {
        // 1. Update TCP server state indicator
        final var server = TcpListenerServer.getInstance();
        if (tcpServerStatus != null) {
            if (server != null && server.isRunning()) {
                tcpServerStatus.setText("[TCP Server: ACTIVE port " + server.getPort() + "]");
                tcpServerStatus.setStyle("-fx-text-fill: #2ea043; -fx-font-weight: bold;");
            } else {
                tcpServerStatus.setText("[TCP Server: INACTIVE]");
                tcpServerStatus.setStyle("-fx-text-fill: #da3633; -fx-font-weight: bold;");
            }
        }

        // 2. Refresh TCP node list from DeviceRegistry filtering for TCP/WiFi connection type
        final List<String> allNodes = DeviceRegistry.getAllNodes();
        final List<String> tcpNodes = allNodes.stream()
            .filter(node -> "TCP/WiFi".equalsIgnoreCase(DeviceRegistry.getConnectionType(node)))
            .toList();

        if (tcpDevicesList != null) {
            final var items = tcpDevicesList.getItems();
            if (!items.equals(tcpNodes)) {
                final String currentSelection = tcpDevicesList.getSelectionModel().getSelectedItem();
                items.setAll(tcpNodes);
                if (currentSelection != null && items.contains(currentSelection)) {
                    tcpDevicesList.getSelectionModel().select(currentSelection);
                }
            }
        }
    }

    /**
     * Opens or focuses the live chart telemetry tab for the specified node identifier.
     *
     * @param nodeId Edge node identifier (e.g. "ESP32-01" or "UNO-01")
     */
    public void openOrSelectTabForNode(final String nodeId) {
        if (nodeId == null || nodeId.isBlank()) return;

        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(() -> openOrSelectTabForNode(nodeId));
            return;
        }

        if (deviceTabs == null) return;

        // Check if tab already exists in deviceTabs
        for (final Tab tab : deviceTabs.getTabs()) {
            if (tab.getText().contains(nodeId)) {
                deviceTabs.getSelectionModel().select(tab);
                return;
            }
        }

        // If not already existing, instantiate tab
        if (pendingTabs.add(nodeId)) {
            try {
                final FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/device_tab.fxml"));
                final Parent tabContent = loader.load();
                final DeviceTabController ctrl = loader.getController();
                ctrl.init(nodeId);

                final Tab tab = new Tab("Node: " + nodeId, tabContent);
                deviceTabs.getTabs().add(tab);
                deviceTabs.getSelectionModel().select(tab);

                tabControllers.put(nodeId, ctrl);
            } catch (final IOException e) {
                System.err.println("Failed to load device tab UI for " + nodeId + ": " + e.getMessage());
                pendingTabs.remove(nodeId);
            }
        }
    }

    @FXML
    public void handleRefreshPorts() {
        refreshPorts();
    }

    /**
     * Actively scans system serial ports and automatically prioritizes Arduino/USB hardware.
     */
    public void refreshPorts() {
        if (comPortCombo == null) return;

        final String currentVal = comPortCombo.getValue();
        comPortCombo.getItems().clear();

        final com.fazecast.jSerialComm.SerialPort[] ports = com.fazecast.jSerialComm.SerialPort.getCommPorts();
        final List<String> priorityPorts = new ArrayList<>();
        final List<String> otherPorts = new ArrayList<>();
        String bestMatch = null;

        for (final com.fazecast.jSerialComm.SerialPort p : ports) {
            final String sysName = p.getSystemPortName();
            final String desc = p.getDescriptivePortName();
            final String portDesc = p.getPortDescription();

            final boolean isArduino = (desc != null && desc.toLowerCase().contains("arduino")) ||
                                (portDesc != null && portDesc.toLowerCase().contains("arduino")) ||
                                sysName.equalsIgnoreCase("COM8");

            final String display = isArduino ? sysName + " (Arduino Uno)" : sysName;

            if (isArduino) {
                priorityPorts.add(display);
                if (bestMatch == null) bestMatch = display;
            } else {
                otherPorts.add(display);
            }
        }

        comPortCombo.getItems().addAll(priorityPorts);
        comPortCombo.getItems().addAll(otherPorts);

        boolean hasCom8 = false;
        for (final String item : comPortCombo.getItems()) {
            if (item.startsWith("COM8")) {
                hasCom8 = true;
                break;
            }
        }
        if (!hasCom8) {
            comPortCombo.getItems().add(0, "COM8 (Arduino Uno)");
            if (bestMatch == null) bestMatch = "COM8 (Arduino Uno)";
        }

        if (currentVal != null && !currentVal.isBlank() && comPortCombo.getItems().contains(currentVal)) {
            comPortCombo.setValue(currentVal);
        } else if (bestMatch != null) {
            comPortCombo.setValue(bestMatch);
        } else if (!comPortCombo.getItems().isEmpty()) {
            comPortCombo.setValue(comPortCombo.getItems().get(0));
        }
    }

    @FXML
    private void handleConnect() {
        final String raw = comPortCombo.getValue();
        if (raw != null && !raw.trim().isEmpty()) {
            final String port = raw.trim().split(" ")[0].trim();
            System.out.println("Starting telemetry on " + port);

            if (activeReader != null) {
                activeReader.close();
            }

            openOrSelectTabForNode("UNO-01");

            final var queue = GatewayPipeline.getGlobalIngressQueue();
            if (queue != null) {
                activeReader = new SerialReaderRunnable(port, 115200, queue);
                DeviceRegistry.registerSerialNode("UNO-01");

                if (RedflagApp.GLOBAL_MITIGATION_CONTROLLER != null) {
                    RedflagApp.GLOBAL_MITIGATION_CONTROLLER.setSerialLink(activeReader);
                }
                final Thread t = new Thread(activeReader, "serial-reader-" + port);
                t.setDaemon(true);
                t.start();
            }
        }
    }

    private void startInternalSimulation() {
        openOrSelectTabForNode("SIM-01");
        if (simRunning.compareAndSet(false, true)) {
            simThread = new Thread(this::runInternalSimulationLoop, "internal-simulator-thread");
            simThread.setDaemon(true);
            simThread.start();
        }
    }

    private void stopInternalSimulation() {
        simRunning.set(false);
    }

    private void runInternalSimulationLoop() {
        long seq = 0;
        long virtualUs = System.currentTimeMillis() * 1000L;
        int cycle = 0;
        final Random rnd = new Random();

        while (simRunning.get()) {
            final var queue = GatewayPipeline.getGlobalIngressQueue();
            if (queue == null) {
                try { Thread.sleep(200); } catch (final InterruptedException ignored) { break; }
                continue;
            }

            cycle++;
            final boolean attack = (cycle > 20 && cycle <= 120);
            if (cycle > 120) {
                cycle = 0;
                virtualUs += 6_000_000L;
            }

            final String proto;
            final String state;
            final int bytes;
            final long sleepMs;

            if (!attack) {
                virtualUs += 1_000_000L + rnd.nextInt(80) - 40;
                proto = "tcp";
                state = "CON";
                bytes = 114 + rnd.nextInt(13);
                sleepMs = 1000;
            } else {
                virtualUs += 400 + rnd.nextInt(10) - 5;
                proto = "udp";
                state = "INT";
                bytes = 58 + rnd.nextInt(5);
                sleepMs = 10;
            }

            final String line = String.format(java.util.Locale.ROOT,
                "{\"v\":1,\"node\":\"SIM-01\",\"seq\":%d,\"st\":%d,\"proto\":\"%s\",\"state\":\"%s\",\"flgs\":\"e\",\"bytes\":%d}",
                seq++, virtualUs, proto, state, bytes);

            queue.offer(new RawPacket(line, System.nanoTime()));

            try {
                Thread.sleep(sleepMs);
            } catch (final InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    @Override
    public void onFrame(final TelemetryFrame frame) {
        if (frame.packet() == null) return;
        final String nodeId = frame.packet().nodeId();
        if (nodeId == null || nodeId.isBlank()) return;

        final DeviceTabController controller = tabControllers.get(nodeId);
        if (controller != null) {
            controller.onFrame(frame);
        } else {
            openOrSelectTabForNode(nodeId);
        }
    }

    @FXML
    private void handleLogout() {
        System.out.println("Logging out...");
        if (tcpRefreshTimeline != null) {
            tcpRefreshTimeline.stop();
        }
        Platform.exit();
        System.exit(0);
    }
}
