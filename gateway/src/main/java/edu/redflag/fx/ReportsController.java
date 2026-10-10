package edu.redflag.fx;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.scene.control.TableView;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

public class ReportsController {
    @FXML private TableView<ReportEntry> reportsTable;

    public void initialize() {
        refreshReports();
    }

    @FXML
    private void refreshReports() {
        ObservableList<ReportEntry> data = FXCollections.observableArrayList();
        try (Connection conn = edu.redflag.persist.Db.get(); Statement stmt = conn.createStatement()) {
            ResultSet rs = stmt.executeQuery("SELECT id, node_id, started_at, vector, peak_score, packets_blocked FROM mitigation_events ORDER BY id DESC LIMIT 100");
            while (rs.next()) {
                data.add(new ReportEntry(
                    rs.getInt("id"),
                    rs.getString("node_id"),
                    rs.getString("started_at"),
                    rs.getString("vector"),
                    rs.getDouble("peak_score"),
                    rs.getInt("packets_blocked")
                ));
            }
        } catch (Exception e) {
            System.err.println("Failed to fetch reports: " + e.getMessage());
        }
        reportsTable.setItems(data);
    }

    @FXML
    private void handleExportCsv() {
        javafx.stage.FileChooser fileChooser = new javafx.stage.FileChooser();
        fileChooser.setTitle("Save Report");
        fileChooser.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter("CSV Files", "*.csv"));
        java.io.File file = fileChooser.showSaveDialog(reportsTable.getScene().getWindow());

        if (file != null) {
            try (java.io.PrintWriter writer = new java.io.PrintWriter(file)) {
                writer.println("ID,Node ID,Started At,Vector,Peak Score,Packets Blocked");
                for (ReportEntry e : reportsTable.getItems()) {
                    writer.printf("%d,%s,%s,%s,%.4f,%d\n",
                        e.getId(), e.getNodeId(), e.getStartedAt(),
                        e.getVector(), e.getPeakScore(), e.getPacketsBlocked());
                }
            } catch (Exception ex) {
                System.err.println("Failed to export: " + ex.getMessage());
            }
        }
    }

    public static class ReportEntry {
        private final int id;
        private final String nodeId;
        private final String startedAt;
        private final String vector;
        private final double peakScore;
        private final int packetsBlocked;

        public ReportEntry(int id, String nodeId, String startedAt, String vector, double peakScore, int packetsBlocked) {
            this.id = id; this.nodeId = nodeId; this.startedAt = startedAt;
            this.vector = vector; this.peakScore = peakScore; this.packetsBlocked = packetsBlocked;
        }

        public int getId() { return id; }
        public String getNodeId() { return nodeId; }
        public String getStartedAt() { return startedAt; }
        public String getVector() { return vector; }
        public double getPeakScore() { return peakScore; }
        public int getPacketsBlocked() { return packetsBlocked; }
    }
}
