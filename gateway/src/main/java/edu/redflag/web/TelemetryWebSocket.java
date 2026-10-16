package edu.redflag.web;

import edu.redflag.event.TelemetryObserver;
import edu.redflag.model.TelemetryFrame;
import jakarta.websocket.OnClose;
import jakarta.websocket.OnError;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.server.ServerEndpoint;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * WebSocket endpoint streaming live classified frames directly to browser frontends.
 * Listens on /ws/telemetry for global multi-node feeds.
 */
@ServerEndpoint("/ws/telemetry")
public class TelemetryWebSocket {

    private static final Set<Session> GLOBAL_SESSIONS = new CopyOnWriteArraySet<>();
    private static final ConcurrentHashMap<String, Set<Session>> NODE_SESSIONS = new ConcurrentHashMap<>();

    @OnOpen
    public void onOpen(final Session session) {
        GLOBAL_SESSIONS.add(session);
        System.out.println("[WS] Global dashboard client connected: " + session.getId() + " (Total active: " + GLOBAL_SESSIONS.size() + ")");
    }

    @OnClose
    public void onClose(final Session session) {
        GLOBAL_SESSIONS.remove(session);
        System.out.println("[WS] Global dashboard client disconnected: " + session.getId());
    }

    @OnError
    public void onError(final Session session, final Throwable throwable) {
        GLOBAL_SESSIONS.remove(session);
        try {
            session.close();
        } catch (final Exception ignored) {}
    }

    /**
     * Registers a session subscribed to a specific IoT node stream.
     *
     * @param nodeId  unique device identifier or "all"
     * @param session active WebSocket session
     */
    public static void registerNodeSession(final String nodeId, final Session session) {
        if (nodeId == null || nodeId.isBlank() || nodeId.equalsIgnoreCase("all")) {
            GLOBAL_SESSIONS.add(session);
        } else {
            NODE_SESSIONS.computeIfAbsent(nodeId, k -> new CopyOnWriteArraySet<>()).add(session);
        }
        System.out.println("[WS] Node client connected for [" + nodeId + "]: " + session.getId());
    }

    /**
     * Unregisters a node-specific WebSocket session.
     *
     * @param nodeId  unique device identifier or "all"
     * @param session closed WebSocket session
     */
    public static void unregisterNodeSession(final String nodeId, final Session session) {
        if (nodeId == null || nodeId.isBlank() || nodeId.equalsIgnoreCase("all")) {
            GLOBAL_SESSIONS.remove(session);
        } else {
            final Set<Session> subs = NODE_SESSIONS.get(nodeId);
            if (subs != null) {
                subs.remove(session);
                if (subs.isEmpty()) {
                    NODE_SESSIONS.remove(nodeId);
                }
            }
        }
    }

    /**
     * Singleton observer attached to the gateway event bus.
     */
    public static final class Broadcaster implements TelemetryObserver {
        private final ConcurrentHashMap<String, Long> lastBroadcastNanos = new ConcurrentHashMap<>();

        @Override
        public void onFrame(final TelemetryFrame frame) {
            if (GLOBAL_SESSIONS.isEmpty() && NODE_SESSIONS.isEmpty()) {
                return;
            }

            final var packet = frame.packet();
            final String nodeId = (packet != null && packet.nodeId() != null) ? packet.nodeId() : "unknown";
            final String proto = (packet != null && packet.proto() != null) ? packet.proto().toUpperCase() : "UDP";
            final String state = (packet != null && packet.state() != null) ? packet.state() : "INT";
            final int bytes = (packet != null) ? packet.bytes() : 60;
            final long seq = (packet != null) ? packet.seq() : 0L;
            final long inferUs = frame.inferNanos() > 0 ? (frame.inferNanos() / 1000L) : 0L;
            final String connType = (nodeId.toLowerCase().contains("esp") || nodeId.toLowerCase().contains("tcp")) ? "TCP/WiFi" : "SERIAL";

            final long now = System.nanoTime();
            final long last = lastBroadcastNanos.getOrDefault(nodeId, 0L);

            // Cap flood push rate to 50 fps (20 ms window) per node during heavy attacks to prevent network saturation
            if (frame.attack() && (now - last < 20_000_000L)) {
                return;
            }
            lastBroadcastNanos.put(nodeId, now);

            final double dt = Double.isNaN(frame.deltaTMs()) ? 0.0 : frame.deltaTMs();
            final double std20 = Double.isNaN(frame.deltaTStd20()) ? 0.0 : frame.deltaTStd20();
            final double score = Double.isNaN(frame.score()) ? 0.0 : frame.score();

            final String jsonPayload = String.format(Locale.ROOT,
                "{\"node\":\"%s\",\"nodeId\":\"%s\",\"seq\":%d,\"proto\":\"%s\",\"state\":\"%s\",\"bytes\":%d,\"dt\":%.4f,\"delta_t_ms\":%.4f,\"std20\":%.4f,\"dt_std_20\":%.4f,\"score\":%.4f,\"attack\":%b,\"isAttack\":%b,\"http\":%d,\"httpStatus\":%d,\"inferUs\":%d,\"infer_us\":%d,\"connectionType\":\"%s\"}",
                nodeId, nodeId, seq, proto, state, bytes,
                dt, dt, std20, std20, score,
                frame.attack(), frame.attack(),
                frame.httpStatus(), frame.httpStatus(),
                inferUs, inferUs, connType
            );

            // Broadcast to all global dashboard clients
            for (final Session s : GLOBAL_SESSIONS) {
                if (s.isOpen()) {
                    try {
                        s.getAsyncRemote().sendText(jsonPayload);
                    } catch (final Exception ignored) {}
                } else {
                    GLOBAL_SESSIONS.remove(s);
                }
            }

            // Broadcast to node-specific subscribers
            final Set<Session> nodeSubs = NODE_SESSIONS.get(nodeId);
            if (nodeSubs != null && !nodeSubs.isEmpty()) {
                for (final Session s : nodeSubs) {
                    if (s.isOpen()) {
                        try {
                            s.getAsyncRemote().sendText(jsonPayload);
                        } catch (final Exception ignored) {}
                    } else {
                        nodeSubs.remove(s);
                    }
                }
            }
        }
    }
}
