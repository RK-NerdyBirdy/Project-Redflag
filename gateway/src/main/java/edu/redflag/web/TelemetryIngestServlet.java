package edu.redflag.web;

import edu.redflag.model.RawPacket;
import edu.redflag.persist.Db;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;
import java.util.concurrent.BlockingQueue;

/**
 * REST API Servlet supporting:
 * GET /api/telemetry?node=UNO-01&limit=100 -> JSON array of recent logs
 * POST /api/telemetry -> Remote edge nodes pushing telemetry frames over HTTP
 */
public class TelemetryIngestServlet extends HttpServlet {
    private final BlockingQueue<RawPacket> queue;
    private final edu.redflag.core.DeviceRegistry deviceRegistry;

    public TelemetryIngestServlet() {
        this.queue = null;
        this.deviceRegistry = null;
    }

    public TelemetryIngestServlet(BlockingQueue<RawPacket> queue, edu.redflag.core.DeviceRegistry registry) {
        this.queue = queue;
        this.deviceRegistry = registry;
    }

    @SuppressWarnings("unchecked")
    private BlockingQueue<RawPacket> getIngressQueue() {
        if (this.queue != null) {
            return this.queue;
        }
        Object attr = getServletContext().getAttribute("ingressQueue");
        if (attr instanceof BlockingQueue) {
            return (BlockingQueue<RawPacket>) attr;
        }
        return edu.redflag.core.GatewayPipeline.getGlobalIngressQueue();
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String body = new String(req.getInputStream().readNBytes(4096), StandardCharsets.UTF_8).trim();
        if (body.isEmpty() || body.charAt(0) != '{') {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST, "JSON frame payload required");
            return;
        }
        
        // Fast manual parse to extract node ID to register it early
        int idx = body.indexOf("\"node\":\"");
        if (idx != -1) {
            int end = body.indexOf("\"", idx + 8);
            if (end != -1) {
                String nodeId = body.substring(idx + 8, end);
                if (deviceRegistry != null) {
                    deviceRegistry.getSession(nodeId);
                }
            }
        }

        BlockingQueue<RawPacket> q = getIngressQueue();
        if (q != null && q.offer(new RawPacket(body, System.nanoTime()))) {
            resp.setStatus(HttpServletResponse.SC_ACCEPTED);
        } else {
            System.err.println("[HTTP INGEST] Error: Ingress queue unavailable or full!");
            resp.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Ingress queue full or unavailable");
        }
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        final String node = req.getParameter("node");
        int limit = 100;
        try {
            if (req.getParameter("limit") != null) {
                limit = Math.min(Integer.parseInt(req.getParameter("limit")), 1000);
            }
        } catch (NumberFormatException ignored) {}

        resp.setContentType("application/json;charset=UTF-8");

        final boolean filterByNode = (node != null && !node.isBlank() && !node.equalsIgnoreCase("all"));
        final String sql = filterByNode
            ? "SELECT node_id, seq, delta_t_ms, dt_std_20, bytes, proto, state, score, is_attack, http_status, logged_at FROM telemetry_logs WHERE node_id = ? ORDER BY id DESC LIMIT ?"
            : "SELECT node_id, seq, delta_t_ms, dt_std_20, bytes, proto, state, score, is_attack, http_status, logged_at FROM telemetry_logs ORDER BY id DESC LIMIT ?";

        try (Connection conn = Db.get();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            if (filterByNode) {
                ps.setString(1, node);
                ps.setInt(2, limit);
            } else {
                ps.setInt(1, limit);
            }

            try (ResultSet rs = ps.executeQuery();
                 PrintWriter out = resp.getWriter()) {

                out.write("[");
                boolean first = true;
                while (rs.next()) {
                    if (!first) out.write(",");
                    first = false;

                    final String nodeId = rs.getString("node_id");
                    final String proto = rs.getString("proto");
                    final String state = rs.getString("state");
                    final double dt = rs.getDouble("delta_t_ms");
                    final double std20 = rs.getDouble("dt_std_20");
                    final double score = rs.getDouble("score");
                    final boolean isAttack = rs.getInt("is_attack") == 1;

                    out.write(String.format(Locale.ROOT,
                        "{\"nodeId\":\"%s\",\"node\":\"%s\",\"seq\":%d,\"proto\":\"%s\",\"state\":\"%s\",\"bytes\":%d,\"delta_t\":\"%.3f\",\"dt\":%.4f,\"dt_std20\":\"%.4f\",\"std20\":%.4f,\"score\":%.4f,\"isAtk\":%b,\"attack\":%b,\"http\":%d,\"httpStatus\":%d,\"time\":\"%s\"}",
                        nodeId != null ? nodeId : "unknown",
                        nodeId != null ? nodeId : "unknown",
                        rs.getLong("seq"),
                        proto != null ? proto.toUpperCase() : "UDP",
                        state != null ? state : "INT",
                        rs.getInt("bytes"),
                        dt, dt, std20, std20, score,
                        isAttack, isAttack,
                        rs.getInt("http_status"),
                        rs.getInt("http_status"),
                        rs.getString("logged_at") != null ? rs.getString("logged_at") : ""
                    ));
                }
                out.write("]");
            }
        } catch (SQLException e) {
            resp.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Database error: " + e.getMessage());
        }
    }
}
