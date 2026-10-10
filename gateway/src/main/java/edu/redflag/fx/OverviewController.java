package edu.redflag.fx;

import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

public class OverviewController {
    @FXML private Label lblActiveDevices;
    @FXML private Label lblTotalPackets;
    @FXML private Label lblMitigated;
    @FXML private TableView<?> alertsTable;

    public void initialize() {
        refreshStats();
    }

    private void refreshStats() {
        try (Connection conn = edu.redflag.persist.Db.get(); Statement stmt = conn.createStatement()) {
            ResultSet rs1 = stmt.executeQuery("SELECT COUNT(*) FROM nodes");
            if (rs1.next()) lblActiveDevices.setText(String.valueOf(rs1.getInt(1)));
            rs1.close();

            ResultSet rs2 = stmt.executeQuery("SELECT SUM(packets_blocked) FROM mitigation_events");
            if (rs2.next()) lblTotalPackets.setText(String.valueOf(rs2.getInt(1)));
            rs2.close();

            ResultSet rs3 = stmt.executeQuery("SELECT COUNT(*) FROM mitigation_events");
            if (rs3.next()) lblMitigated.setText(String.valueOf(rs3.getInt(1)));
            rs3.close();
        } catch (Exception e) {
            System.err.println("Failed to fetch overview stats: " + e.getMessage());
        }
    }
}
