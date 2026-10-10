package edu.redflag.model;

/**
 * Classification of threat type detected by the edge pipeline.
 */
public enum AttackVector {
    NONE,
    UDP_FLOOD,
    TCP_SYN_FLOOD,
    UNKNOWN
}
