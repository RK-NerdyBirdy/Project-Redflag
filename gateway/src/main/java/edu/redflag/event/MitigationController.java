package edu.redflag.event;

import edu.redflag.alert.EmailAlerter;
import edu.redflag.io.SerialReaderRunnable;
import edu.redflag.model.TelemetryFrame;

import java.util.Comparator;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Controller enforcing mitigation decisions (HTTP 403 vs 200), serial hardware feedback,
 * and dispatching automated email alerts upon attack onset.
 */
public final class MitigationController implements TelemetryObserver {

    /**
     * Immutable alert record stored in priority order.
     *
     * @param severity    Alert severity rating (higher indicates greater threat)
     * @param timestampMs Epoch timestamp in milliseconds
     * @param nodeId      Target IoT node ID
     * @param score       Inference threat score
     */
    public record Alert(int severity, long timestampMs, String nodeId, float score) {}

    private final PriorityBlockingQueue<Alert> alerts = new PriorityBlockingQueue<>(
        64,
        Comparator.comparingInt(Alert::severity).reversed().thenComparing(Alert::timestampMs, Comparator.reverseOrder())
    );

    private volatile SerialReaderRunnable serialLink;
    private final AtomicBoolean underAttack = new AtomicBoolean(false);

    private final java.util.Set<String> isolatedNodes = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public MitigationController() {
        this.serialLink = null;
    }

    /**
     * Manually isolates an edge node and commands physical serial hardware to block traffic.
     *
     * @param nodeId Edge node identifier
     */
    public void isolateNode(final String nodeId) {
        isolatedNodes.add(nodeId);
        if (serialLink != null) {
            serialLink.writeCommand("BLOCK"); // force block hardware
        }
    }

    /**
     * Registers the active serial hardware reader link for bidirectional control.
     *
     * @param link Serial communication runnable
     */
    public void setSerialLink(final SerialReaderRunnable link) {
        this.serialLink = link;
    }

    /**
     * Returns the queue of mitigation alerts.
     *
     * @return priority blocking queue of alerts
     */
    public PriorityBlockingQueue<Alert> getAlerts() {
        return alerts;
    }

    /**
     * Returns whether any monitored node is currently under attack.
     *
     * @return true if under attack; false otherwise
     */
    public boolean isUnderAttack() {
        return underAttack.get();
    }

    /**
     * Handles attack mitigation action by dispatching an automated alert.
     *
     * @param nodeId Target IoT edge node ID
     * @param score  Confidence threat score
     * @param vector Detected volumetric DDoS vector
     */
    public void onAttack(final String nodeId, final double score, final String vector) {
        EmailAlerter.sendAttackAlert(nodeId, score, vector);
    }

    @Override
    public void onFrame(final TelemetryFrame frame) {
        if (frame.buffering()) {
            return;
        }

        final var nodeId = frame.packet().nodeId();
        final boolean effectivelyAttack = frame.attack() || isolatedNodes.contains(nodeId);

        final boolean wasAttacking = underAttack.getAndSet(effectivelyAttack);

        // Rising edge detection: trigger alert on attack onset
        if (effectivelyAttack && !wasAttacking) {
            final int severity = (frame.score() > 0.99f) ? 3 : 2;
            alerts.offer(new Alert(severity, System.currentTimeMillis(), nodeId, frame.score()));
            final var vector = frame.vector() != null ? frame.vector().name() : "VOLUMETRIC_FLOOD";
            onAttack(nodeId, (double) frame.score(), vector);
            edu.redflag.fx.MainLayoutController.triggerAttackBanner(nodeId, (double) frame.score());
        }

        // On state change, send feedback command to physical edge device
        if (effectivelyAttack != wasAttacking && serialLink != null) {
            serialLink.writeCommand(effectivelyAttack ? "BLOCK" : "SAFE");
        }
    }
}
