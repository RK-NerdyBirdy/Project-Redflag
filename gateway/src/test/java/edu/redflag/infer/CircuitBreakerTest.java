package edu.redflag.infer;

import edu.redflag.core.CircuitBreaker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class CircuitBreakerTest {

    @Test
    public void testCircuitTrippingAndRecovery() throws InterruptedException {
        CircuitBreaker cb = new CircuitBreaker(3, 50); // 3 failures, 50ms cooldown
        assertTrue(cb.allow());
        assertFalse(cb.isOpen());

        cb.recordFailure();
        cb.recordFailure();
        assertTrue(cb.allow());

        // 3rd failure trips the breaker
        cb.recordFailure();
        assertTrue(cb.isOpen());
        assertFalse(cb.allow());

        // Wait for cooldown
        Thread.sleep(60);

        // Half-open probe allowed
        assertTrue(cb.allow());

        // Success resets the circuit
        cb.recordSuccess();
        assertFalse(cb.isOpen());
        assertTrue(cb.allow());
    }
}
