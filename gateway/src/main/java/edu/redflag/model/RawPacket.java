package edu.redflag.model;

/**
 * Immutable carrier for a raw line ingested from serial or network intake with high-resolution reception timestamp.
 */
public record RawPacket(String line, long rxNanos) {}
