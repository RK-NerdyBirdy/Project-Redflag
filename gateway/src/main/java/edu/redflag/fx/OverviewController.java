package edu.redflag.fx;

import edu.redflag.persist.Db;
import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.PieChart;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.chart.XYChart;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Controller for the Global Overview SOC dashboard.
 * Displays real-time packet counts, attack stats, node health, latency, uptime,
 * multi-node attack score timelines, protocol distribution, and historical logs.
 */
public class OverviewController {

    @FXML private Label statTotalPackets;
    @FXML private Label statAttacks;
    @FXML private Label statActiveNodes;
    @FXML private Label statNodesAttacked;
    @FXML private Label statLatency;
    @FXML private Label statUptime;

    @FXML private LineChart<Number, Number> attackScoreChart;
    @FXML private NumberAxis scoreXAxis;
    @FXML private NumberAxis scoreYAxis;
    @FXML private PieChart protocolPieChart;

    @FXML private TableView<RecentEvent> recentEventsTable;
    @FXML private TableColumn<RecentEvent, String> colNodeId;
    @FXML private TableColumn<RecentEvent, Long> colSeq;
    @FXML private TableColumn<RecentEvent, String> colDeltaT;
    @FXML private TableColumn<RecentEvent, String> colScore;
    @FXML private TableColumn<RecentEvent, String> colVerdict;
    @FXML private TableColumn<RecentEvent, String> colLoggedAt;

    private static volatile OverviewController instance;

    /**
     * Immutable data model for the recent events table.
     *
     * @param nodeId   originating node identifier
     * @param seq      packet sequence number
     * @param deltaT   inter-arrival time string in milliseconds
     * @param score    attack probability score string
     * @param verdict  ATTACK or BENIGN verdict
     * @param loggedAt timestamp of the log entry
     */
    public record RecentEvent(String nodeId, long seq, String deltaT, String score, String verdict, String loggedAt) {}

    /**
     * Immutable data model for a single score point on the timeline chart.
     *
     * @param frameIndex sequence index of the frame
     * @param nodeId     originating node identifier
     * @param score      evaluated attack probability score
     */
    public record FramePoint(long frameIndex, String nodeId, double score) {}

    /**
     * Singleton metrics accumulator for edge gateway telemetry.
     */
    public static final class MetricsHolder {
        private final AtomicLong totalPackets = new AtomicLong(0L);
        private final AtomicLong totalAttacks = new AtomicLong(0L);
        private final AtomicLong totalInferUs = new AtomicLong(0L);
        private final AtomicLong inferCount = new AtomicLong(0L);
        private final ConcurrentHashMap<String, Long> lastSeenNodes = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, Long> nodesUnderAttack = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, AtomicLong> protocolCounts = new ConcurrentHashMap<>();
        private final ConcurrentLinkedQueue<FramePoint> frameQueue = new ConcurrentLinkedQueue<>();
        private final AtomicLong frameSeq = new AtomicLong(0L);
    }

    private static final MetricsHolder COUNTERS = new MetricsHolder();

    private final Map<String, XYChart.Series<Number, Number>> nodeSeriesMap = new HashMap<>();
    private final Map<String, PieChart.Data> pieDataMap = new HashMap<>();
    private final ObservableList<PieChart.Data> pieDataList = FXCollections.observableArrayList();

    private long startTime;
    private ScheduledExecutorService dbScheduler;

    /**
     * Retrieves the singleton controller instance.
     *
     * @return active OverviewController instance, or null if uninitialized
     */
    public static OverviewController getInstance() {
        return instance;
    }

    /**
     * Ingests a classified packet metric from the gateway pipeline or telemetry event bus.
     *
     * @param nodeId   the unique identifier of the originating IoT device
     * @param isAttack true if classified as an attack frame
     * @param score    continuous attack probability score [0.0, 1.0]
     * @param inferUs  inference latency in microseconds
     */
    public static void recordPacket(final String nodeId, final boolean isAttack, final double score, final long inferUs) {
        COUNTERS.totalPackets.incrementAndGet();
        if (isAttack) {
            COUNTERS.totalAttacks.incrementAndGet();
        }
        final var now = System.currentTimeMillis();
        final var nodeKey = (nodeId != null && !nodeId.isBlank()) ? nodeId : "unknown";
        COUNTERS.lastSeenNodes.put(nodeKey, now);
        if (isAttack) {
            COUNTERS.nodesUnderAttack.put(nodeKey, now);
        }
        if (inferUs > 0) {
            COUNTERS.totalInferUs.addAndGet(inferUs);
            COUNTERS.inferCount.incrementAndGet();
        }
        final var index = COUNTERS.frameSeq.incrementAndGet();
        COUNTERS.frameQueue.offer(new FramePoint(index, nodeKey, score));

        // Keep queue bounded
        while (COUNTERS.frameQueue.size() > 500) {
            COUNTERS.frameQueue.poll();
        }
    }

    /**
     * Ingests a protocol distribution sample into the protocol pie chart counters.
     *
     * @param proto protocol name (e.g., UDP, TCP, ICMP)
     */
    public static void recordProtocol(final String proto) {
        final var key = (proto == null || proto.isBlank()) ? "OTHER" : proto.toUpperCase();
        COUNTERS.protocolCounts.computeIfAbsent(key, k -> new AtomicLong(0L)).incrementAndGet();
    }

    /**
     * Computes the number of currently active or registered IoT edge nodes.
     *
     * @return count of active nodes
     */
    public static int getActiveNodesCount() {
        final var now = System.currentTimeMillis();
        final var cutoffActive = now - 60_000L;
        int count = (int) COUNTERS.lastSeenNodes.values().stream()
                .filter(ts -> ts >= cutoffActive).count();
        if (count == 0 && !COUNTERS.lastSeenNodes.isEmpty()) {
            count = COUNTERS.lastSeenNodes.size();
        }
        if (count == 0) {
            count = edu.redflag.core.DeviceRegistry.getAllNodes().size();
        }
        return Math.max(1, count);
    }

    /**
     * Retrieves the total volumetric attacks detected across the gateway session.
     *
     * @return attack event count
     */
    public static long getTotalAttacksCount() {
        return COUNTERS.totalAttacks.get();
    }

    /**
     * Initializes TableView bindings, chart series, animation timers, and background DB pollers.
     */
    @FXML
    public void initialize() {
        instance = this;
        startTime = System.currentTimeMillis();

        // 1. Setup TableView column factories
        if (colNodeId != null) colNodeId.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().nodeId()));
        if (colSeq != null) colSeq.setCellValueFactory(d -> new SimpleObjectProperty<>(d.getValue().seq()));
        if (colDeltaT != null) colDeltaT.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().deltaT()));
        if (colScore != null) colScore.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().score()));
        if (colVerdict != null) colVerdict.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().verdict()));
        if (colLoggedAt != null) colLoggedAt.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().loggedAt()));

        // 2. Setup PieChart and LineChart
        if (protocolPieChart != null) {
            protocolPieChart.setData(pieDataList);
            protocolPieChart.setAnimated(false);
            protocolPieChart.setLegendVisible(true);
            protocolPieChart.setLabelsVisible(true);
        }
        if (attackScoreChart != null) {
            attackScoreChart.setAnimated(false);
            attackScoreChart.setCreateSymbols(false);
        }

        // 3. Seed initial historical counts from DB if present
        loadHistoricalDbCounters();

        // 4. Start 1-second UI refresh timer for stat cards and charts
        final var timer = new AnimationTimer() {
            private long lastUpdate = 0;

            @Override
            public void handle(final long now) {
                if (now - lastUpdate >= 1_000_000_000L) {
                    lastUpdate = now;
                    updateDashboardCards();
                    updateTimelineChart();
                    updateProtocolChart();
                }
            }
        };
        timer.start();

        // 5. Query recent events from DB every 5 seconds on background thread
        dbScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            final var t = new Thread(r, "overview-db-poller");
            t.setDaemon(true);
            return t;
        });
        dbScheduler.scheduleAtFixedRate(this::pollRecentEventsFromDb, 0, 5, TimeUnit.SECONDS);
    }

    private void updateDashboardCards() {
        if (statTotalPackets != null) {
            statTotalPackets.setText(String.format("%,d", COUNTERS.totalPackets.get()));
        }
        if (statAttacks != null) {
            statAttacks.setText(String.format("%,d", COUNTERS.totalAttacks.get()));
        }

        // Active nodes (seen within the last 60 seconds)
        final var now = System.currentTimeMillis();
        final var cutoffActive = now - 60_000L;
        var activeNodesCount = COUNTERS.lastSeenNodes.values().stream()
                .filter(ts -> ts >= cutoffActive).count();
        if (activeNodesCount == 0 && !COUNTERS.lastSeenNodes.isEmpty()) {
            activeNodesCount = COUNTERS.lastSeenNodes.size();
        }
        if (statActiveNodes != null) {
            statActiveNodes.setText(String.valueOf(activeNodesCount));
        }

        // Nodes currently under attack (attack seen within last 10 seconds)
        final var cutoffAttack = now - 10_000L;
        final var attackedCount = COUNTERS.nodesUnderAttack.values().stream()
                .filter(ts -> ts >= cutoffAttack).count();
        if (statNodesAttacked != null) {
            statNodesAttacked.setText(String.valueOf(attackedCount));
            if (attackedCount > 0) {
                statNodesAttacked.setStyle("-fx-text-fill: #ff4444; -fx-font-size: 32px; -fx-font-weight: bold;");
            } else {
                statNodesAttacked.setStyle("-fx-text-fill: #00cc66; -fx-font-size: 32px; -fx-font-weight: bold;");
            }
        }

        // Average inference latency (µs)
        final var count = COUNTERS.inferCount.get();
        final var avgLat = count > 0 ? (COUNTERS.totalInferUs.get() / count) : 0L;
        if (statLatency != null) {
            statLatency.setText(String.valueOf(avgLat));
        }

        // System uptime formatted HH:MM:SS
        final var elapsedSec = (now - startTime) / 1000L;
        final var hh = elapsedSec / 3600L;
        final var mm = (elapsedSec % 3600L) / 60L;
        final var ss = elapsedSec % 60L;
        if (statUptime != null) {
            statUptime.setText(String.format("%02d:%02d:%02d", hh, mm, ss));
        }
    }

    private void updateTimelineChart() {
        if (attackScoreChart == null) return;

        final Map<String, List<XYChart.Data<Number, Number>>> batchPerNode = new HashMap<>();
        FramePoint point;
        long maxSeq = 0;
        while ((point = COUNTERS.frameQueue.poll()) != null) {
            final var nodeId = point.nodeId();
            batchPerNode.computeIfAbsent(nodeId, k -> new ArrayList<>())
                        .add(new XYChart.Data<>(point.frameIndex(), point.score()));
            if (point.frameIndex() > maxSeq) {
                maxSeq = point.frameIndex();
            }
        }

        for (final var entry : batchPerNode.entrySet()) {
            final var nodeId = entry.getKey();
            final var batch = entry.getValue();
            var series = nodeSeriesMap.get(nodeId);
            if (series == null) {
                series = new XYChart.Series<>();
                series.setName(nodeId);
                nodeSeriesMap.put(nodeId, series);
                attackScoreChart.getData().add(series);
            }
            final var data = series.getData();
            final int overflow = (data.size() + batch.size()) - 100;
            if (overflow > 0) {
                final int removeCount = Math.min(overflow, data.size());
                for (int i = 0; i < removeCount; i++) {
                    data.remove(0);
                }
            }
            data.addAll(batch);
        }

        if (maxSeq > 0 && scoreXAxis != null) {
            final var lower = Math.max(0, maxSeq - 100);
            final var upper = Math.max(100, maxSeq);
            if (scoreXAxis.getUpperBound() != upper) {
                scoreXAxis.setLowerBound(lower);
                scoreXAxis.setUpperBound(upper);
            }
        }
    }

    private void updateProtocolChart() {
        if (protocolPieChart == null) return;

        long total = 0L;
        for (final var count : COUNTERS.protocolCounts.values()) {
            total += count.get();
        }

        for (final var entry : COUNTERS.protocolCounts.entrySet()) {
            final var proto = entry.getKey();
            final var count = entry.getValue().get();
            final double pct = total > 0 ? (count * 100.0 / total) : 0.0;
            final String label = String.format(Locale.ROOT, "%s: %d (%.1f%%)", proto, count, pct);
            var data = pieDataMap.get(proto);
            if (data == null) {
                data = new PieChart.Data(label, count);
                pieDataMap.put(proto, data);
                pieDataList.add(data);
            } else {
                data.setName(label);
                data.setPieValue(count);
            }
        }
    }

    private void loadHistoricalDbCounters() {
        Thread.ofVirtual().name("overview-seed-worker").start(() -> {
            try (Connection conn = Db.get(); Statement stmt = conn.createStatement()) {
                // Preload counts from existing DB tables
                try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM telemetry_logs")) {
                    if (rs.next()) {
                        final var total = rs.getLong(1);
                        COUNTERS.totalPackets.compareAndSet(0L, total);
                    }
                }
                try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM telemetry_logs WHERE is_attack = 1")) {
                    if (rs.next()) {
                        final var attacks = rs.getLong(1);
                        COUNTERS.totalAttacks.compareAndSet(0L, attacks);
                    }
                }
                try (ResultSet rs = stmt.executeQuery("SELECT node_id FROM nodes")) {
                    final var now = System.currentTimeMillis();
                    while (rs.next()) {
                        COUNTERS.lastSeenNodes.put(rs.getString("node_id"), now);
                    }
                }
                try (ResultSet rs = stmt.executeQuery("SELECT proto, COUNT(*) FROM telemetry_logs GROUP BY proto")) {
                    while (rs.next()) {
                        final var proto = rs.getString(1);
                        final var cnt = rs.getLong(2);
                        if (proto != null && !proto.isBlank()) {
                            COUNTERS.protocolCounts.computeIfAbsent(proto.toUpperCase(), k -> new AtomicLong(0L)).addAndGet(cnt);
                        }
                    }
                }
            } catch (Exception ex) {
                System.err.println("[OVERVIEW] Initial metrics seeding notice: " + ex.getMessage());
            }
        });
    }

    private void pollRecentEventsFromDb() {
        try (Connection conn = Db.get();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                 "SELECT node_id, seq, delta_t_ms, score, is_attack, logged_at FROM telemetry_logs ORDER BY id DESC LIMIT 20")) {
            final var list = new ArrayList<RecentEvent>();
            while (rs.next()) {
                final var nodeId = rs.getString("node_id");
                final var seq = rs.getLong("seq");
                final var dt = rs.getDouble("delta_t_ms");
                final var score = rs.getDouble("score");
                final var isAttack = rs.getInt("is_attack") == 1;
                final var loggedAt = rs.getString("logged_at");

                list.add(new RecentEvent(
                    nodeId != null ? nodeId : "-",
                    seq,
                    String.format("%.2f", dt),
                    String.format("%.3f", score),
                    isAttack ? "ATTACK" : "BENIGN",
                    loggedAt != null ? loggedAt : "-"
                ));
            }
            Platform.runLater(() -> {
                if (recentEventsTable != null) {
                    if (recentEventsTable.getItems() == null || recentEventsTable.getItems().isEmpty()) {
                        recentEventsTable.setItems(FXCollections.observableArrayList(list));
                    } else {
                        recentEventsTable.getItems().setAll(list);
                    }
                }
            });
        } catch (Exception e) {
            System.err.println("[OVERVIEW] Recent telemetry query error: " + e.getMessage());
        }
    }
}
