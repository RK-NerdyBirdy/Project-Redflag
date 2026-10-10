package edu.redflag.persist;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class NodeDao {
    public List<String> getAllNodeIds() {
        List<String> nodes = new ArrayList<>();
        try (Connection conn = Db.get();
             PreparedStatement ps = conn.prepareStatement("SELECT node_id FROM nodes");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                nodes.add(rs.getString("node_id"));
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return nodes;
    }
}
