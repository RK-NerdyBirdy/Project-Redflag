package edu.redflag.fx;

import edu.redflag.event.TelemetryObserver;
import edu.redflag.model.TelemetryFrame;
import javafx.animation.AnimationTimer;
import javafx.fxml.FXML;
import javafx.scene.chart.AreaChart;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Controller for an individual device telemetry visualization tab.
 * Implements a high-throughput, coalesced render loop maintaining real-time charts
 * for packet inter-arrival time (Δt), rolling dispersion (σ_20), and attack probability scores.
 */
public class DeviceTabController implements TelemetryObserver {
    private static final int MAX_SERIES_POINTS = 200;

    @FXML private LineChart<Number, Number> deltaChart;
    @FXML private LineChart<Number, Number> stdChart;
    @FXML private AreaChart<Number, Number> scoreChart;

    @FXML private NumberAxis deltaXAxis;
    @FXML private NumberAxis stdXAxis;
    @FXML private NumberAxis scoreXAxis;

    @FXML private Label statusBadge;
    @FXML private Label lblThroughput;
    @FXML private Label lblLatency;
    @FXML private Label lblHttp;
    @FXML private ProgressBar scoreGauge;
    @FXML private Button btnDismiss;
    @FXML private Button btnIsolate;

    private final XYChart.Series<Number, Number> seriesDelta = new XYChart.Series<>();
    private final XYChart.Series<Number, Number> seriesStd   = new XYChart.Series<>();
    private final XYChart.Series<Number, Number> seriesScore = new XYChart.Series<>();

    private final ConcurrentLinkedQueue<TelemetryFrame> inbox = new ConcurrentLinkedQueue<>();
    private final AtomicLong packetCounter = new AtomicLong(0L);

    private long lastRenderNanos = 0;
    private long lastRateTickNanos = System.nanoTime();
    private long lastRatePacketCount = 0L;
    private boolean wasAttacking = false;
    private String nodeId;

    private AnimationTimer renderLoop;

    /**
     * Initializes the telemetry tab for the specified node identifier, configuring chart axes,
     * disabling symbol allocation, and starting the coalesced ~30 FPS render loop.
     *
     * @param nodeId Unique IoT edge node identifier
     */
    public void init(final String nodeId) {
        this.nodeId = nodeId;
        seriesDelta.setName("Δt (ms)");
        seriesStd.setName("σ_20 (ms)");
        seriesScore.setName("P(Attack)");

        deltaChart.getData().add(seriesDelta);
        stdChart.getData().add(seriesStd);
        scoreChart.getData().add(seriesScore);

        deltaChart.setAnimated(false);
        stdChart.setAnimated(false);
        scoreChart.setAnimated(false);

        deltaChart.setCreateSymbols(false);
        stdChart.setCreateSymbols(false);
        scoreChart.setCreateSymbols(false);

        if (deltaXAxis != null) { deltaXAxis.setAutoRanging(false); deltaXAxis.setAnimated(false); }
        if (stdXAxis != null)   { stdXAxis.setAutoRanging(false);   stdXAxis.setAnimated(false); }
        if (scoreXAxis != null) { scoreXAxis.setAutoRanging(false); scoreXAxis.setAnimated(false); }

        renderLoop = new AnimationTimer() {
            @Override
            public void handle(final long now) {
                // Coalesce render passes to ~30 FPS (every 33ms) to avoid saturating JavaFX UI thread
                if (now - lastRenderNanos < 33_000_000L) {
                    return;
                }
                lastRenderNanos = now;
                drain();
            }
        };
        renderLoop.start();
    }

    @Override
    public void onFrame(final TelemetryFrame frame) {
        if (inbox.size() > 5000) {
            inbox.poll(); // Evict oldest under extreme backpressure
        }
        inbox.offer(frame);
    }

    private void drain() {
        if (inbox.isEmpty()) return;

        final List<XYChart.Data<Number, Number>> deltaBatch = new ArrayList<>();
        final List<XYChart.Data<Number, Number>> stdBatch   = new ArrayList<>();
        final List<XYChart.Data<Number, Number>> scoreBatch = new ArrayList<>();

        TelemetryFrame frame;
        TelemetryFrame lastFrame = null;
        int processedInPulse = 0;
        long latestSeq = 0;

        while ((frame = inbox.poll()) != null && processedInPulse < 100) {
            final var packet = frame.packet();
            if (packet != null) {
                final long seq = packet.seq();
                latestSeq = seq;
                deltaBatch.add(new XYChart.Data<>(seq, Double.isNaN(frame.deltaTMs()) ? 0.0 : frame.deltaTMs()));
                stdBatch.add(new XYChart.Data<>(seq, Double.isNaN(frame.deltaTStd20()) ? 0.0 : frame.deltaTStd20()));
                scoreBatch.add(new XYChart.Data<>(seq, (double) frame.score()));
            }

            lastFrame = frame;
            processedInPulse++;
            packetCounter.incrementAndGet();
        }

        if (!deltaBatch.isEmpty()) {
            trimAndAppend(seriesDelta, deltaBatch);
            trimAndAppend(seriesStd, stdBatch);
            trimAndAppend(seriesScore, scoreBatch);

            final double lower = Math.max(0, latestSeq - MAX_SERIES_POINTS);
            final double upper = Math.max(MAX_SERIES_POINTS, latestSeq);

            if (deltaXAxis != null && deltaXAxis.getUpperBound() != upper) {
                deltaXAxis.setLowerBound(lower);
                deltaXAxis.setUpperBound(upper);
            }
            if (stdXAxis != null && stdXAxis.getUpperBound() != upper) {
                stdXAxis.setLowerBound(lower);
                stdXAxis.setUpperBound(upper);
            }
            if (scoreXAxis != null && scoreXAxis.getUpperBound() != upper) {
                scoreXAxis.setLowerBound(lower);
                scoreXAxis.setUpperBound(upper);
            }
        }

        if (lastFrame != null) {
            renderMetrics(lastFrame);
        }
    }

    private void trimAndAppend(final XYChart.Series<Number, Number> series, final List<XYChart.Data<Number, Number>> batch) {
        final var data = series.getData();
        final int overflow = (data.size() + batch.size()) - MAX_SERIES_POINTS;
        if (overflow > 0) {
            final int removeCount = Math.min(overflow, data.size());
            for (int i = 0; i < removeCount; i++) {
                data.remove(0);
            }
        }
        data.addAll(batch);
    }

    private void renderMetrics(final TelemetryFrame f) {
        if (f.buffering()) {
            if (statusBadge != null) {
                statusBadge.setText("BUFFERING W=20");
                statusBadge.setStyle("-fx-background-color: #333333; -fx-text-fill: #aaaaaa; -fx-padding: 6 16; -fx-background-radius: 12;");
            }
            if (scoreGauge != null) scoreGauge.setProgress(0.0);
            if (lblLatency != null) lblLatency.setText("infer: warm-up");
            if (lblHttp != null) lblHttp.setText("HTTP 200 [INITIALIZING]");
            return;
        }

        final boolean isAttack = f.attack();

        if (statusBadge != null) {
            statusBadge.setStyle("");
            statusBadge.setText(isAttack ? "UNDER ATTACK" : "SAFE");
            statusBadge.getStyleClass().removeAll("badge-safe", "badge-attack");
            statusBadge.getStyleClass().add(isAttack ? "badge-attack" : "badge-safe");
        }

        if (scoreGauge != null) {
            scoreGauge.setProgress(Math.max(0.0, Math.min(1.0, f.score())));
        }
        if (lblLatency != null) {
            lblLatency.setText(String.format("infer: %d µs", f.inferNanos() / 1000L));
        }
        if (lblHttp != null) {
            lblHttp.setText("HTTP " + f.httpStatus() + (f.httpStatus() == 403 ? " [MITIGATED]" : " [PASSED]"));
        }

        final long now = System.nanoTime();
        if (now - lastRateTickNanos > 1_000_000_000L) {
            final long currentCount = packetCounter.get();
            final long deltaPackets = currentCount - lastRatePacketCount;
            if (lblThroughput != null) {
                lblThroughput.setText(deltaPackets + " pkt/s");
            }
            lastRatePacketCount = currentCount;
            lastRateTickNanos = now;
        }

        if (isAttack && !wasAttacking) {
            final var packet = f.packet();
            final String id = packet != null ? packet.nodeId() : nodeId;
            triggerAlertPopup(id, f.score());
            if (btnDismiss != null) btnDismiss.setVisible(true);
        }
        wasAttacking = isAttack;
    }

    private void triggerAlertPopup(final String targetNode, final float score) {
        MainLayoutController.triggerAttackBanner(targetNode, score);
    }

    @FXML
    private void handleDismiss() {
        if (btnDismiss != null) btnDismiss.setVisible(false);
    }

    @FXML
    private void handleIsolate() {
        if (RedflagApp.GLOBAL_MITIGATION_CONTROLLER != null) {
            RedflagApp.GLOBAL_MITIGATION_CONTROLLER.isolateNode(nodeId);
            System.out.println("[MITIGATION] Node " + nodeId + " isolated by user action. Emitting hardware block.");
            if (statusBadge != null) {
                statusBadge.setText("NODE ISOLATED");
                statusBadge.setStyle("-fx-background-color: #da3633; -fx-text-fill: #ffffff; -fx-font-weight: bold;");
            }
        }
    }
}
