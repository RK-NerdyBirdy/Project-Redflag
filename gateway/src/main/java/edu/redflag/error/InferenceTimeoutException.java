package edu.redflag.error;

/**
 * Checked exception thrown when an inference invocation exceeds the real-time deadline.
 */
public class InferenceTimeoutException extends RedflagException {
    public InferenceTimeoutException(long millis) {
        super("Inference deadline exceeded (" + millis + " ms)");
    }

    public InferenceTimeoutException(String message) {
        super(message);
    }
}
