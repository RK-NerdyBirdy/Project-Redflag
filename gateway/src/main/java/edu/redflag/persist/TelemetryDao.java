package edu.redflag.persist;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public class TelemetryDao {
    public long getTelemetryCountForNode(String nodeId) {
        try (Connection conn = Db.get();
             PreparedStatement ps = conn.prepareStatement("SELECT count(*) FROM telemetry_logs WHERE node_id = ?")) {
            ps.setString(1, nodeId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getLong(1);
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return 0L;
    }
}
