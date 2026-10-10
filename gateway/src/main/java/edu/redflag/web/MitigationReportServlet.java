package edu.redflag.web;

import edu.redflag.persist.Db;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Report servlet rendering the HTML / JSP mitigation audit dashboard.
 * Includes user session tracking.
 */
public class MitigationReportServlet extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        HttpSession session = req.getSession(true);
        Integer views = (Integer) session.getAttribute("reportViews");
        session.setAttribute("reportViews", (views == null) ? 1 : (views + 1));

        List<Map<String, Object>> events = new ArrayList<>();
        List<Map<String, Object>> nodes = new ArrayList<>();

        String eventsSql = "SELECT id, node_id, started_at, ended_at, vector, peak_score, packets_blocked "
            + "FROM mitigation_events ORDER BY id DESC LIMIT 50";

        String nodesSql = "SELECT n.node_id, n.last_seen, "
            + "(SELECT count(*) FROM mitigation_events m WHERE m.node_id = n.node_id) AS attack_count "
            + "FROM nodes n ORDER BY n.last_seen DESC";

        try (Connection conn = Db.get()) {
            try (PreparedStatement ps1 = conn.prepareStatement(eventsSql);
                 ResultSet rs1 = ps1.executeQuery()) {
                while (rs1.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs1.getLong("id"));
                    row.put("node", rs1.getString("node_id"));
                    row.put("start", rs1.getString("started_at"));
                    row.put("end", rs1.getString("ended_at"));
                    row.put("vector", rs1.getString("vector"));
                    row.put("peak", rs1.getFloat("peak_score"));
                    row.put("blocked", rs1.getLong("packets_blocked"));
                    events.add(row);
                }
            }

            try (PreparedStatement ps2 = conn.prepareStatement(nodesSql);
                 ResultSet rs2 = ps2.executeQuery()) {
                while (rs2.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("node", rs2.getString(1));
                    row.put("lastSeen", rs2.getString(2));
                    row.put("attacks", rs2.getLong(3));
                    nodes.add(row);
                }
            }
        } catch (SQLException e) {
            throw new ServletException("Database query error in MitigationReportServlet", e);
        }

        req.setAttribute("events", events);
        req.setAttribute("nodes", nodes);
        req.getRequestDispatcher("/WEB-INF/views/mitigations.jsp").forward(req, resp);
    }
}
