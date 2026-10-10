package edu.redflag.persist;

import edu.redflag.event.TelemetryObserver;
import edu.redflag.model.Packet;
import edu.redflag.model.TelemetryFrame;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Asynchronous, transactional batched audit logger.
 * Never blocks the real-time inference path: drops persistence rows if database becomes saturated.
 */
public final class AuditWriter implements TelemetryObserver, AutoCloseable {
    private static final int BATCH_SIZE = 200;
    private static final long FLUSH_INTERVAL_MS = 250;

    private final BlockingQueue<TelemetryFrame> queue = new LinkedBlockingQueue<>(20_000);
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Thread workerThread;
    private final Set<String> knownNodes = ConcurrentHashMap.newKeySet();

    private boolean inAttack = false;
    private long currentMitigationEventId = -1L;

    public AuditWriter() {
        this.workerThread = new Thread(this::auditLoop, "audit-writer");
        this.workerThread.setDaemon(true);
        this.workerThread.start();
    }

    @Override
    public void onFrame(TelemetryFrame frame) {
        if (frame.buffering()) return;
        // Drop-oldest audit row policy under back-pressure to protect inference throughput
        if (!queue.offer(frame)) {
            queue.poll();
            queue.offer(frame);
        }
    }

    private void auditLoop() {
        List<TelemetryFrame> buffer = new ArrayList<>(BATCH_SIZE);
        while (running.get() || !queue.isEmpty()) {
            try {
                TelemetryFrame first = queue.poll(FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS);
                if (first != null) {
                    buffer.add(first);
                    queue.drainTo(buffer, BATCH_SIZE - 1);
                }
                if (!buffer.isEmpty()) {
                    flushBatch(buffer);
                    buffer.clear();
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void flushBatch(List<TelemetryFrame> frames) {
        try (Connection conn = Db.get()) {
            conn.setAutoCommit(false);

            boolean sqlite = Db.isSqliteMode();
            String upsertNodeSql = sqlite
                ? "INSERT INTO nodes(node_id, last_seen) VALUES (?, datetime('now')) ON CONFLICT(node_id) DO UPDATE SET last_seen = datetime('now')"
                : "INSERT INTO nodes(node_id, last_seen) VALUES (?, now()) ON CONFLICT (node_id) DO UPDATE SET last_seen = now()";

            String insertTelSql = "INSERT INTO telemetry_logs(node_id, seq, stime_us, delta_t_ms, dt_std_20, bytes, proto, state, flgs, score, is_attack, infer_us, http_status)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

            try (PreparedStatement psNode = conn.prepareStatement(upsertNodeSql);
                 PreparedStatement psTel  = conn.prepareStatement(insertTelSql)) {

                // 1. Register distinct nodes
                for (TelemetryFrame frame : frames) {
                    String nodeId = frame.packet().nodeId();
                    if (knownNodes.add(nodeId)) {
                        psNode.setString(1, nodeId);
                        psNode.addBatch();
                    }
                }
                psNode.executeBatch();

                // 2. Insert telemetry rows
                for (TelemetryFrame f : frames) {
                    Packet p = f.packet();
                    int i = 1;
                    psTel.setString(i++, p.nodeId());
                    psTel.setLong(i++, p.seq());
                    psTel.setLong(i++, p.stimeUs());
                    psTel.setDouble(i++, Double.isNaN(f.deltaTMs()) ? 0.0 : f.deltaTMs());
                    psTel.setDouble(i++, Double.isNaN(f.deltaTStd20()) ? 0.0 : f.deltaTStd20());
                    psTel.setInt(i++, p.bytes());
                    psTel.setString(i++, p.proto());
                    psTel.setString(i++, p.state());
                    psTel.setString(i++, p.flgs());
                    psTel.setFloat(i++, f.score());
                    psTel.setInt(i++, f.attack() ? 1 : 0);
                    psTel.setInt(i++, (int) (f.inferNanos() / 1000L)); // nanoseconds to microseconds
                    psTel.setInt(i++, f.httpStatus());
                    psTel.addBatch();
                }
                psTel.executeBatch();

                // 3. Track mitigation lifecycle episodes
                recordMitigationEvents(conn, frames, sqlite);

                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                System.err.println("[AUDIT] Transaction rollback: " + e.getMessage());
            }
        } catch (SQLException e) {
            System.err.println("[AUDIT] Database connection error: " + e.getMessage());
        }
    }

    private void recordMitigationEvents(Connection conn, List<TelemetryFrame> frames, boolean sqlite) throws SQLException {
        String openSql = sqlite
            ? "INSERT INTO mitigation_events(node_id, started_at, vector, peak_score) VALUES (?, datetime('now'), ?, ?)"
            : "INSERT INTO mitigation_events(node_id, started_at, vector, peak_score) VALUES (?, now(), ?, ?) RETURNING id";

        String closeSql = sqlite
            ? "UPDATE mitigation_events SET ended_at = datetime('now'), packets_blocked = packets_blocked + ? WHERE id = ?"
            : "UPDATE mitigation_events SET ended_at = now(), packets_blocked = packets_blocked + ? WHERE id = ?";

        long blockedInBatch = 0;
        for (TelemetryFrame frame : frames) {
            if (frame.attack() && !inAttack) {
                // Rising edge: Start attack episode
                try (PreparedStatement ps = conn.prepareStatement(openSql, PreparedStatement.RETURN_GENERATED_KEYS)) {
                    ps.setString(1, frame.packet().nodeId());
                    ps.setString(2, frame.vector().name());
                    ps.setFloat(3, frame.score());
                    ps.executeUpdate();
                    try (ResultSet rs = ps.getGeneratedKeys()) {
                        if (rs.next()) {
                            currentMitigationEventId = rs.getLong(1);
                        }
                    }
                }
                inAttack = true;
                blockedInBatch = 0;
            }

            if (frame.attack() && frame.httpStatus() == 403) {
                blockedInBatch++;
            }

            if (!frame.attack() && inAttack) {
                // Falling edge: End attack episode
                if (currentMitigationEventId > 0) {
                    try (PreparedStatement ps = conn.prepareStatement(closeSql)) {
                        ps.setLong(1, blockedInBatch);
                        ps.setLong(2, currentMitigationEventId);
                        ps.executeUpdate();
                    }
                }
                inAttack = false;
                blockedInBatch = 0;
                currentMitigationEventId = -1L;
            }
        }

        if (inAttack && blockedInBatch > 0 && currentMitigationEventId > 0) {
            String updateCountSql = "UPDATE mitigation_events SET packets_blocked = packets_blocked + ? WHERE id = ?";
            try (PreparedStatement ps = conn.prepareStatement(updateCountSql)) {
                ps.setLong(1, blockedInBatch);
                ps.setLong(2, currentMitigationEventId);
                ps.executeUpdate();
            }
        }
    }

    @Override
    public void close() {
        running.set(false);
        try {
            workerThread.join(3000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
