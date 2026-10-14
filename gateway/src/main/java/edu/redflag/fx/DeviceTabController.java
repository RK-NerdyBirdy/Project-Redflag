package edu.redflag.fx;

import edu.redflag.event.TelemetryObserver;
import edu.redflag.model.TelemetryFrame;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.chart.AreaChart;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class DeviceTabController implements TelemetryObserver {
    private static final int MAX_SERIES_POINTS = 200;

    @FXML private LineChart<Number, Number> deltaChart;
    @FXML private LineChart<Number, Number> stdChart;
    @FXML private AreaChart<Number, Number> scoreChart;

    @FXML private javafx.scene.chart.NumberAxis deltaXAxis;
    @FXML private javafx.scene.chart.NumberAxis stdXAxis;
    @FXML private javafx.scene.chart.NumberAxis scoreXAxis;

    @FXML private Label statusBadge;
    @FXML private Label lblThroughput;
    @FXML private Label lblLatency;
    @FXML private Label lblHttp;
    @FXML private ProgressBar scoreGauge;
    @FXML private Button btnDismiss;

    private final XYChart.Series<Number, Number> seriesDelta = new XYChart.Series<>();
    private final XYChart.Series<Number, Number> seriesStd   = new XYChart.Series<>();
    private final XYChart.Series<Number, Number> seriesScore = new XYChart.Series<>();

    private final ConcurrentLinkedQueue<TelemetryFrame> inbox = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean drainScheduled = new AtomicBoolean(false);
    private final AtomicLong packetCounter = new AtomicLong(0L);

    private long lastRateTickNanos = System.nanoTime();
    private long lastRatePacketCount = 0L;
    private boolean wasAttacking = false;
    private String nodeId;

    private javafx.animation.AnimationTimer renderLoop;

    public void init(String nodeId) {
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

        renderLoop = new javafx.animation.AnimationTimer() {
            @Override
            public void handle(long now) {
                drain();
            }
        };
        renderLoop.start();
    }

    @Override
    public void onFrame(TelemetryFrame frame) {
        if (inbox.size() > 5000) inbox.poll();
        inbox.offer(frame);
    }

    private void drain() {
        if (inbox.isEmpty()) return;

        TelemetryFrame lastFrame = null;
        java.util.List<XYChart.Data<Number, Number>> deltaBatch = new java.util.ArrayList<>();
        java.util.List<XYChart.Data<Number, Number>> stdBatch = new java.util.ArrayList<>();
        java.util.List<XYChart.Data<Number, Number>> scoreBatch = new java.util.ArrayList<>();

        TelemetryFrame frame;
        int processedInPulse = 0;
        long latestSeq = 0;

        while ((frame = inbox.poll()) != null && processedInPulse < 100) {
            long seq = frame.packet().seq();
            latestSeq = seq;
            deltaBatch.add(new XYChart.Data<>(seq, Double.isNaN(frame.deltaTMs()) ? 0.0 : frame.deltaTMs()));
            stdBatch.add(new XYChart.Data<>(seq, Double.isNaN(frame.deltaTStd20()) ? 0.0 : frame.deltaTStd20()));
            scoreBatch.add(new XYChart.Data<>(seq, frame.score()));

            lastFrame = frame;
            processedInPulse++;
            packetCounter.incrementAndGet();
        }

        if (!deltaBatch.isEmpty()) {
            seriesDelta.getData().addAll(deltaBatch);
            seriesStd.getData().addAll(stdBatch);
            seriesScore.getData().addAll(scoreBatch);

            trimSeries(seriesDelta);
            trimSeries(seriesStd);
            trimSeries(seriesScore);

            // Slide X-axis window smoothly without triggering expensive layout passes
            double lower = Math.max(0, latestSeq - MAX_SERIES_POINTS);
            double upper = Math.max(MAX_SERIES_POINTS, latestSeq);
            if (deltaXAxis != null) {
                deltaXAxis.setLowerBound(lower);
                deltaXAxis.setUpperBound(upper);
            }
            if (stdXAxis != null) {
                stdXAxis.setLowerBound(lower);
                stdXAxis.setUpperBound(upper);
            }
            if (scoreXAxis != null) {
                scoreXAxis.setLowerBound(lower);
                scoreXAxis.setUpperBound(upper);
            }
        }

        if (lastFrame != null) {
            renderMetrics(lastFrame);
        }
    }

    private void trimSeries(XYChart.Series<Number, Number> series) {
        int excess = series.getData().size() - MAX_SERIES_POINTS;
        if (excess > 0) {
            series.getData().subList(0, excess).clear();
        }
    }

    private void renderMetrics(TelemetryFrame f) {
        boolean isAttack = f.attack();

        statusBadge.setText(isAttack ? "UNDER ATTACK" : "SAFE");
        statusBadge.getStyleClass().removeAll("badge-safe", "badge-attack");
        statusBadge.getStyleClass().add(isAttack ? "badge-attack" : "badge-safe");

        scoreGauge.setProgress(f.score());
        lblLatency.setText(String.format("infer: %d µs", f.inferNanos() / 1000L));
        lblHttp.setText("HTTP " + f.httpStatus() + (f.httpStatus() == 403 ? " [MITIGATED]" : " [PASSED]"));

        long now = System.nanoTime();
        if (now - lastRateTickNanos > 1_000_000_000L) {
            long currentCount = packetCounter.get();
            long deltaPackets = currentCount - lastRatePacketCount;
            lblThroughput.setText(deltaPackets + " pkt/s");
            lastRatePacketCount = currentCount;
            lastRateTickNanos = now;
        }

        if (isAttack && !wasAttacking) {
            triggerAlertPopup(f.packet().nodeId(), f.score());
            btnDismiss.setVisible(true);
        }
        wasAttacking = isAttack;
    }

    private void triggerAlertPopup(String nodeId, float score) {
        MainLayoutController.triggerAttackBanner(nodeId, score);
    }

    @FXML
    private void handleDismiss() {
        btnDismiss.setVisible(false);
    }

    @FXML
    private void handleIsolate() {
        if (edu.redflag.fx.RedflagApp.GLOBAL_MITIGATION_CONTROLLER != null) {
            edu.redflag.fx.RedflagApp.GLOBAL_MITIGATION_CONTROLLER.isolateNode(nodeId);
            System.out.println("Node " + nodeId + " isolated by user.");
            Alert alert = new Alert(Alert.AlertType.INFORMATION, "Node " + nodeId + " has been isolated. Sending hardware block.");
            alert.show();
        }
    }
}
