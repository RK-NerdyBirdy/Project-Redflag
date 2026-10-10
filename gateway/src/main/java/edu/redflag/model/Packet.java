package edu.redflag.model;

/**
 * Parsed immutable IoT packet representation based on BoT-IoT / Argus flow schema.
 *
 * @param nodeId   Stable edge node identifier (e.g., "UNO-01")
 * @param seq      Monotonic packet sequence counter from node
 * @param stimeUs  Virtual or real timestamp in microseconds (unsigned 32-bit wrapped or unwrapped)
 * @param proto    Transport protocol (e.g., "tcp", "udp", "icmp")
 * @param state    Flow state (e.g., "CON", "INT", "REQ")
 * @param flgs     Network flags (e.g., "e", "e s", "e d")
 * @param bytes    Payload bytes size
 */
public record Packet(
    String nodeId,
    long seq,
    long stimeUs,
    String proto,
    String state,
    String flgs,
    int bytes
) {}
