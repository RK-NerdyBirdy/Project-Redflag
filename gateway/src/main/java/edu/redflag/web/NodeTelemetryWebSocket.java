package edu.redflag.web;

import jakarta.websocket.OnClose;
import jakarta.websocket.OnError;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.server.PathParam;
import jakarta.websocket.server.ServerEndpoint;

/**
 * WebSocket endpoint streaming live telemetry frames for a specific node identifier.
 * Listens on /ws/telemetry/{nodeId} (used by device.jsp).
 */
@ServerEndpoint("/ws/telemetry/{nodeId}")
public class NodeTelemetryWebSocket {

    @OnOpen
    public void onOpen(final Session session, @PathParam("nodeId") final String nodeId) {
        session.getUserProperties().put("nodeId", nodeId);
        TelemetryWebSocket.registerNodeSession(nodeId, session);
    }

    @OnClose
    public void onClose(final Session session) {
        final String nodeId = (String) session.getUserProperties().get("nodeId");
        TelemetryWebSocket.unregisterNodeSession(nodeId, session);
    }

    @OnError
    public void onError(final Session session, final Throwable throwable) {
        onClose(session);
        try {
            session.close();
        } catch (final Exception ignored) {}
    }
}
