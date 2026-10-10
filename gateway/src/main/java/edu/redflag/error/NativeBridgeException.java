package edu.redflag.error;

/**
 * Unchecked exception thrown on XGBoost4J JNI / off-heap memory or bridge failures.
 * Used to trigger Circuit Breaker degradation without bubbling up fatal unchecked JVM panics.
 */
public class NativeBridgeException extends RuntimeException {
    public NativeBridgeException(String message) {
        super(message);
    }

    public NativeBridgeException(String message, Throwable cause) {
        super(message, cause);
    }
}
