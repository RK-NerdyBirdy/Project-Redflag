package edu.redflag.error;

/**
 * Checked exception indicating an unparseable or semantically invalid packet.
 * Recoverable: pipeline drops the corrupted frame and continues stream ingestion.
 */
public class PacketCorruptedException extends RedflagException {
    public PacketCorruptedException(String message) {
        super(message);
    }

    public PacketCorruptedException(String message, Throwable cause) {
        super(message, cause);
    }
}
