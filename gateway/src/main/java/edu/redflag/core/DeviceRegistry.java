package edu.redflag.core;

import edu.redflag.event.TelemetryObserver;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Registry for device sessions, network/serial device inventories, and per-node telemetry observers.
 * Guarantees a unique {@link NodeSession} per nodeId and provides a {@link TelemetryObserver}
 * that dispatches frames to UI tabs, WebSocket streams, and persistence layers.
 */
public class DeviceRegistry {

    /** Active TCP-connected node identifiers. */
    public static final CopyOnWriteArrayList<String> tcpNodes = new CopyOnWriteArrayList<>();

    /** Active USB/Serial-connected node identifiers. */
    public static final CopyOnWriteArrayList<String> serialNodes = new CopyOnWriteArrayList<>(List.of("UNO-01"));

    private static final Set<String> knownSessions = ConcurrentHashMap.newKeySet();

    private final ConcurrentHashMap<String, NodeSession> sessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, TelemetryObserver> observers = new ConcurrentHashMap<>();

    /**
     * Registers a TCP-connected edge node identifier.
     *
     * @param nodeId unique node identifier string (e.g. "ESP32-01")
     */
    public static void registerTcpNode(final String nodeId) {
        if (nodeId != null && !nodeId.isBlank() && !tcpNodes.contains(nodeId)) {
            tcpNodes.add(nodeId);
        }
    }

    /**
     * Unregisters a TCP-connected edge node identifier.
     *
     * @param nodeId node identifier string to remove
     */
    public static void unregisterTcpNode(final String nodeId) {
        if (nodeId != null) {
            tcpNodes.remove(nodeId);
        }
    }

    /**
     * Registers a USB/Serial edge node identifier.
     *
     * @param nodeId node identifier string (e.g. "UNO-01")
     */
    public static void registerSerialNode(final String nodeId) {
        if (nodeId != null && !nodeId.isBlank() && !serialNodes.contains(nodeId)) {
            serialNodes.add(nodeId);
        }
    }

    /**
     * Unregisters a USB/Serial edge node identifier.
     *
     * @param nodeId node identifier string to remove
     */
    public static void unregisterSerialNode(final String nodeId) {
        if (nodeId != null) {
            serialNodes.remove(nodeId);
        }
    }

    /**
     * Returns the union of all active edge node identifiers across both Serial and TCP/WiFi interfaces.
     *
     * @return immutable list of all known device node identifiers
     */
    public static List<String> getAllNodes() {
        final var all = new LinkedHashSet<String>();
        all.addAll(tcpNodes);
        all.addAll(serialNodes);
        all.addAll(knownSessions);
        if (all.isEmpty()) {
            all.add("UNO-01");
        }
        return List.copyOf(all);
    }

    /**
     * Determines whether a node is connected via TCP/WiFi or Serial.
     *
     * @param nodeId the node identifier to inspect
     * @return "TCP/WiFi" if registered as a TCP node, otherwise "SERIAL"
     */
    public static String getConnectionType(final String nodeId) {
        if (nodeId != null && tcpNodes.contains(nodeId)) {
            return "TCP/WiFi";
        }
        return "SERIAL";
    }

    /**
     * Get or create a {@link NodeSession} for the given node identifier.
     *
     * @param nodeId unique node identifier
     * @return active or newly created node session
     */
    public NodeSession getSession(final String nodeId) {
        Objects.requireNonNull(nodeId, "NodeId must not be null");
        knownSessions.add(nodeId);
        return sessions.computeIfAbsent(nodeId, id -> new NodeSession());
    }

    /**
     * Get or create a {@link TelemetryObserver} for the given node.
     *
     * @param nodeId unique node identifier
     * @return active or newly created device telemetry observer
     */
    public TelemetryObserver getObserver(final String nodeId) {
        Objects.requireNonNull(nodeId, "NodeId must not be null");
        return observers.computeIfAbsent(nodeId, DeviceTelemetryObserver::new);
    }
}
