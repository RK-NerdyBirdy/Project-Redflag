package edu.redflag.error;

/**
 * Checked exception indicating a structural serial or framing protocol violation
 * (e.g., unexpected line termination, missing delimiters, unknown schema prefix).
 */
public class FramingErrorException extends PacketCorruptedException {
    public FramingErrorException(String message) {
        super(message);
    }

    public FramingErrorException(String message, Throwable cause) {
        super(message, cause);
    }
}
