package edu.redflag.core;

import edu.redflag.event.TelemetryObserver;
import edu.redflag.infer.InferenceStrategy;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for device sessions and per‑node telemetry observers.
 * Guarantees a unique NodeSession per nodeId and provides a TelemetryObserver
 * that dispatches frames to UI tabs, WebSocket streams, and persistence layers.
 */
public class DeviceRegistry {
    private final ConcurrentHashMap<String, NodeSession> sessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, TelemetryObserver> observers = new ConcurrentHashMap<>();

    /** Get or create a NodeSession for the given node identifier. */
    public NodeSession getSession(String nodeId) {
        return sessions.computeIfAbsent(nodeId, id -> new NodeSession());
    }

    /** Get or create a TelemetryObserver for the given node. */
    public TelemetryObserver getObserver(String nodeId) {
        return observers.computeIfAbsent(nodeId, id -> new DeviceTelemetryObserver(id));
    }
}
