package edu.redflag.fx;

import edu.redflag.event.TelemetryObserver;
import edu.redflag.model.TelemetryFrame;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.ToggleButton;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class DevicesController implements TelemetryObserver {

    @FXML private TabPane deviceTabs;
    @FXML private Label statusBadge;
    @FXML private ToggleButton mitigationToggle;
    @FXML private ToggleButton simulationToggle;
    @FXML private javafx.scene.control.ComboBox<String> comPortCombo;

    private AtomicBoolean mitigationFlag;
    private final AtomicBoolean simRunning = new AtomicBoolean(false);
    private Thread simThread;
    
    private final ConcurrentHashMap<String, DeviceTabController> tabControllers = new ConcurrentHashMap<>();

    public void bindMitigationFlag(AtomicBoolean flag) {
        this.mitigationFlag = flag;
    }

    @FXML
    private void initialize() {
        if (comPortCombo != null) {
            comPortCombo.setEditable(true);
            comPortCombo.setVisibleRowCount(10);
            comPortCombo.setOnShowing(e -> refreshPorts());
            refreshPorts();
        }

        edu.redflag.event.TelemetryObservable bus = edu.redflag.core.GatewayPipeline.getGlobalEventBus();
        if (bus != null) bus.subscribe(this);
        edu.redflag.core.GatewayPipeline p = edu.redflag.core.GatewayPipeline.getInstance();
        if (p != null) {
            mitigationFlag = p.mitigationFlag();
        }

        mitigationToggle.selectedProperty().addListener((obs, oldVal, newVal) -> {
            if (mitigationFlag != null) {
                mitigationFlag.set(newVal);
            }
            mitigationToggle.setText("MITIGATION: " + (newVal ? "ON" : "OFF"));
        });

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

        String currentVal = comPortCombo.getValue();
        comPortCombo.getItems().clear();

        com.fazecast.jSerialComm.SerialPort[] ports = com.fazecast.jSerialComm.SerialPort.getCommPorts();
        java.util.List<String> priorityPorts = new java.util.ArrayList<>();
        java.util.List<String> otherPorts = new java.util.ArrayList<>();
        String bestMatch = null;

        for (com.fazecast.jSerialComm.SerialPort p : ports) {
            String sysName = p.getSystemPortName();
            String desc = p.getDescriptivePortName();
            String portDesc = p.getPortDescription();

            boolean isArduino = (desc != null && desc.toLowerCase().contains("arduino")) ||
                                (portDesc != null && portDesc.toLowerCase().contains("arduino")) ||
                                sysName.equalsIgnoreCase("COM8");

            String display = isArduino ? sysName + " (Arduino Uno)" : sysName;

            if (isArduino) {
                priorityPorts.add(display);
                if (bestMatch == null) bestMatch = display;
            } else {
                otherPorts.add(display);
            }
        }

        // Add priority USB devices first
        comPortCombo.getItems().addAll(priorityPorts);
        comPortCombo.getItems().addAll(otherPorts);

        // Fallback: If COM8 wasn't in enumeration yet, ensure COM8 is in list
        boolean hasCom8 = false;
        for (String item : comPortCombo.getItems()) {
            if (item.startsWith("COM8")) {
                hasCom8 = true;
                break;
            }
        }
        if (!hasCom8) {
            comPortCombo.getItems().add(0, "COM8 (Arduino Uno)");
            if (bestMatch == null) bestMatch = "COM8 (Arduino Uno)";
        }

        // Auto-selection: keep user choice, else auto-select Arduino/COM8
        if (currentVal != null && !currentVal.isBlank() && comPortCombo.getItems().contains(currentVal)) {
            comPortCombo.setValue(currentVal);
        } else if (bestMatch != null) {
            comPortCombo.setValue(bestMatch);
        } else if (!comPortCombo.getItems().isEmpty()) {
            comPortCombo.setValue(comPortCombo.getItems().get(0));
        }
    }

    private edu.redflag.io.SerialReaderRunnable activeReader;

    @FXML
    private void handleConnect() {
        String raw = comPortCombo.getValue();
        if (raw != null && !raw.trim().isEmpty()) {
            // Strip any descriptive suffix (e.g., "COM8 (Arduino Uno)" -> "COM8")
            String port = raw.trim().split(" ")[0].trim();
            System.out.println("Starting telemetry on " + port);
            
            if (activeReader != null) {
                activeReader.close();
            }
            
            var queue = edu.redflag.core.GatewayPipeline.getGlobalIngressQueue();
            if (queue != null) {
                activeReader = new edu.redflag.io.SerialReaderRunnable(port, 115200, queue);
                if (edu.redflag.fx.RedflagApp.GLOBAL_MITIGATION_CONTROLLER != null) {
                    edu.redflag.fx.RedflagApp.GLOBAL_MITIGATION_CONTROLLER.setSerialLink(activeReader);
                }
                Thread t = new Thread(activeReader);
                t.setDaemon(true);
                t.start();
            }
        }
    }

    private void startInternalSimulation() {
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
        java.util.Random rnd = new java.util.Random();

        while (simRunning.get()) {
            var queue = edu.redflag.core.GatewayPipeline.getGlobalIngressQueue();
            if (queue == null) {
                try { Thread.sleep(200); } catch (InterruptedException ignored) { break; }
                continue;
            }

            cycle++;
            boolean attack = (cycle > 20 && cycle <= 120);
            if (cycle > 120) {
                cycle = 0;
                virtualUs += 6_000_000L;
            }

            String proto, state;
            int bytes;
            long sleepMs;

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

            String line = String.format(java.util.Locale.ROOT,
                "{\"v\":1,\"node\":\"SIM-01\",\"seq\":%d,\"st\":%d,\"proto\":\"%s\",\"state\":\"%s\",\"flgs\":\"e\",\"bytes\":%d}",
                seq++, virtualUs, proto, state, bytes);

            queue.offer(new edu.redflag.model.RawPacket(line, System.nanoTime()));

            try {
                Thread.sleep(sleepMs);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private final java.util.Set<String> pendingTabs = java.util.concurrent.ConcurrentHashMap.newKeySet();

    @Override
    public void onFrame(TelemetryFrame frame) {
        if (frame.buffering()) return;
        
        String nodeId = frame.packet().nodeId();
        DeviceTabController controller = tabControllers.get(nodeId);
        
        if (controller != null) {
            controller.onFrame(frame);
        } else if (pendingTabs.add(nodeId)) {
            // First time seeing this node, trigger tab creation
            Platform.runLater(() -> {
                try {
                    FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/device_tab.fxml"));
                    Parent tabContent = loader.load();
                    DeviceTabController ctrl = loader.getController();
                    ctrl.init(nodeId);
                    
                    Tab tab = new Tab("Node: " + nodeId, tabContent);
                    deviceTabs.getTabs().add(tab);
                    
                    tabControllers.put(nodeId, ctrl);
                    // We can process this frame now that the controller is ready
                    ctrl.onFrame(frame);
                } catch (IOException e) {
                    System.err.println("Failed to load device tab UI: " + e.getMessage());
                    pendingTabs.remove(nodeId); // allow retry
                }
            });
        }
    }

    @FXML
    private void handleLogout() {
        System.out.println("Logging out...");
        Platform.exit();
        System.exit(0);
    }
}
