package edu.redflag.core;

import edu.redflag.model.Packet;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

public class NodeSessionTest {

    @Test
    public void testBufferingStateTransition() {
        NodeSession session = new NodeSession();
        assertFalse(session.isReady());

        // Feed 9 packets -> should still be buffering
        for (int i = 0; i < 9; i++) {
            Packet p = new Packet("NODE-TEST", i, i * 1_000_000L, "tcp", "CON", "e", 120);
            session.update(p);
            assertFalse(session.isReady());
        }

        // 10th packet -> session transitions to ready
        Packet p10 = new Packet("NODE-TEST", 9, 9 * 1_000_000L, "tcp", "CON", "e", 120);
        boolean ready = session.update(p10);
        assertTrue(ready);
        assertTrue(session.isReady());
    }

    @Test
    public void testInterArrivalTimeCalculation() {
        NodeSession session = new NodeSession();

        // 1 second interval = 1,000,000 microseconds = 1000.0 ms
        Packet p1 = new Packet("NODE-TEST", 1, 1_000_000L, "tcp", "CON", "e", 120);
        session.update(p1);

        Packet p2 = new Packet("NODE-TEST", 2, 2_000_000L, "tcp", "CON", "e", 120);
        session.update(p2);

        assertEquals(1000.0, session.lastDeltaTMs(), 0.01);
    }

    @Test
    public void testSessionResetOnLargeGap() {
        NodeSession session = new NodeSession();

        for (int i = 0; i < 15; i++) {
            Packet p = new Packet("NODE-TEST", i, i * 1_000_000L, "tcp", "CON", "e", 120);
            session.update(p);
        }
        assertTrue(session.isReady());

        // 10-second gap (> 5 seconds RESET_GAP_US)
        Packet postGap = new Packet("NODE-TEST", 15, 25_000_000L, "tcp", "CON", "e", 120);
        session.update(postGap);

        // Session should flush and return to buffering state
        assertFalse(session.isReady());
    }
}
