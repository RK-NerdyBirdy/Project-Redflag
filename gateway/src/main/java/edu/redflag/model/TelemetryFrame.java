package edu.redflag.model;

/**
 * Immutable enriched telemetry frame published by the inference pipeline to observers.
 *
 * @param packet       Underlying decoded packet
 * @param deltaTMs     Inter-arrival time in milliseconds
 * @param deltaTStd20  Rolling standard deviation of delta_t over W=20 window
 * @param score        Attack probability score [0.0, 1.0] from inference
 * @param attack       Boolean attack verdict (score >= 0.5)
 * @param vector       Identified attack vector
 * @param inferNanos   Inference execution duration in nanoseconds
 * @param httpStatus   HTTP mitigation response code (200 for pass, 403 for blocked)
 * @param buffering    True if node session is still buffering initial window (<10 packets)
 */
public record TelemetryFrame(
    Packet packet,
    double deltaTMs,
    double deltaTStd20,
    float score,
    boolean attack,
    AttackVector vector,
    long inferNanos,
    int httpStatus,
    boolean buffering
) {
    /**
     * Factory for frames emitted while a node session is still warming its rolling window.
     */
    public static TelemetryFrame buffering(Packet p) {
        return new TelemetryFrame(
            p,
            Double.NaN,
            Double.NaN,
            0.0f,
            false,
            AttackVector.NONE,
            0L,
            200,
            true
        );
    }
}
