package edu.redflag.core;

import edu.redflag.event.TelemetryObserver;
import edu.redflag.model.TelemetryFrame;
import edu.redflag.event.TelemetryObservable;
import java.util.Objects;

/**
 * Simple observer that forwards telemetry frames to a {@link TelemetryObservable}
 * dedicated to a specific node. It can be subscribed by UI tabs, WebSocket
 * sessions, or persistence components that only care about a single node.
 */
public class DeviceTelemetryObserver implements TelemetryObserver {
    private final String nodeId;
    private final TelemetryObservable nodeBus = new TelemetryObservable();

    public DeviceTelemetryObserver(String nodeId) {
        this.nodeId = Objects.requireNonNull(nodeId);
    }

    /** Subscribe a node‑specific observer (e.g., a UI tab). */
    public void subscribe(TelemetryObserver observer) {
        nodeBus.subscribe(observer);
    }

    /** Unsubscribe a node‑specific observer. */
    public void unsubscribe(TelemetryObserver observer) {
        nodeBus.unsubscribe(observer);
    }

    @Override
    public void onFrame(TelemetryFrame frame) {
        // Forward only frames that belong to this node.
        if (frame != null && nodeId.equals(frame.packet().nodeId())) {
            nodeBus.publish(frame);
        }
    }
}
