package edu.redflag.event;

import edu.redflag.io.SerialReaderRunnable;
import edu.redflag.model.TelemetryFrame;

import java.util.Comparator;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Controller enforcing mitigation decisions (HTTP 403 vs 200) and serial hardware feedback.
 */
public final class MitigationController implements TelemetryObserver {
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

    public void isolateNode(String nodeId) {
        isolatedNodes.add(nodeId);
        if (serialLink != null) {
            serialLink.writeCommand("BLOCK"); // force block hardware
        }
    }

    public void setSerialLink(SerialReaderRunnable link) {
        this.serialLink = link;
    }

    public PriorityBlockingQueue<Alert> getAlerts() {
        return alerts;
    }

    public boolean isUnderAttack() {
        return underAttack.get();
    }

    @Override
    public void onFrame(TelemetryFrame frame) {
        if (frame.buffering()) {
            return;
        }

        String nodeId = frame.packet().nodeId();
        boolean effectivelyAttack = frame.attack() || isolatedNodes.contains(nodeId);

        boolean wasAttacking = underAttack.getAndSet(effectivelyAttack);

        // Rising edge detection: trigger alert on attack onset
        if (effectivelyAttack && !wasAttacking) {
            int severity = (frame.score() > 0.99f) ? 3 : 2;
            alerts.offer(new Alert(severity, System.currentTimeMillis(), nodeId, frame.score()));
        }

        // On state change, send feedback command to physical edge device
        if (effectivelyAttack != wasAttacking && serialLink != null) {
            serialLink.writeCommand(effectivelyAttack ? "BLOCK" : "SAFE");
        }
    }
}
