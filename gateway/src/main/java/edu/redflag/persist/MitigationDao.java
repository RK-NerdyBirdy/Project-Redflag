package edu.redflag.persist;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public class MitigationDao {
    public long getTotalPacketsBlocked() {
        try (Connection conn = Db.get();
             PreparedStatement ps = conn.prepareStatement("SELECT SUM(packets_blocked) FROM mitigation_events");
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return 0L;
    }
}
