package edu.redflag.web;

import edu.redflag.event.TelemetryObserver;
import edu.redflag.model.TelemetryFrame;
import jakarta.websocket.OnClose;
import jakarta.websocket.OnError;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.server.ServerEndpoint;

import java.io.IOException;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

import jakarta.websocket.server.PathParam;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket endpoint streaming live classified frames directly to browser frontends.
 */
@ServerEndpoint("/ws/telemetry/{nodeId}")
public class TelemetryWebSocket {
    // Map of nodeId to Set of connected Sessions
    private static final ConcurrentHashMap<String, Set<Session>> SESSIONS = new ConcurrentHashMap<>();

    @OnOpen
    public void onOpen(Session session, @PathParam("nodeId") String nodeId) {
        SESSIONS.computeIfAbsent(nodeId, k -> new CopyOnWriteArraySet<>()).add(session);
        session.getUserProperties().put("nodeId", nodeId);
    }

    @OnClose
    public void onClose(Session session) {
        String nodeId = (String) session.getUserProperties().get("nodeId");
        if (nodeId != null) {
            Set<Session> subs = SESSIONS.get(nodeId);
            if (subs != null) {
                subs.remove(session);
                if (subs.isEmpty()) {
                    SESSIONS.remove(nodeId);
                }
            }
        }
    }

    @OnError
    public void onError(Session session, Throwable throwable) {
        onClose(session);
    }

    /**
     * Singleton observer attached to the gateway event bus.
     */
    public static final class Broadcaster implements TelemetryObserver {
        private final ConcurrentHashMap<String, Long> lastBroadcastNanos = new ConcurrentHashMap<>();

        @Override
        public void onFrame(TelemetryFrame frame) {
            if (frame.buffering() || SESSIONS.isEmpty()) return;
            
            String nodeId = frame.packet().nodeId();
            Set<Session> nodeSessions = SESSIONS.get(nodeId);
            if (nodeSessions == null || nodeSessions.isEmpty()) return;

            long now = System.nanoTime();
            long last = lastBroadcastNanos.getOrDefault(nodeId, 0L);
            
            // Cap flood push rate to 50 fps (20 ms window) per node
            if (frame.attack() && (now - last < 20_000_000L)) {
                return;
            }
            lastBroadcastNanos.put(nodeId, now);

            String jsonPayload = String.format(Locale.ROOT,
                "{\"node\":\"%s\",\"seq\":%d,\"dt\":%.4f,\"std20\":%.4f,\"score\":%.4f,\"attack\":%b,\"http\":%d}",
                nodeId,
                frame.packet().seq(),
                frame.deltaTMs(),
                frame.deltaTStd20(),
                frame.score(),
                frame.attack(),
                frame.httpStatus()
            );

            for (Session session : nodeSessions) {
                if (!session.isOpen()) {
                    onClose(session);
                    continue;
                }
                synchronized (session) {
                    try {
                        session.getBasicRemote().sendText(jsonPayload);
                    } catch (IOException e) {
                        onClose(session);
                    }
                }
            }
        }
        
        private void onClose(Session session) {
            String nodeId = (String) session.getUserProperties().get("nodeId");
            if (nodeId != null) {
                Set<Session> subs = SESSIONS.get(nodeId);
                if (subs != null) {
                    subs.remove(session);
                }
            }
        }
    }
}
