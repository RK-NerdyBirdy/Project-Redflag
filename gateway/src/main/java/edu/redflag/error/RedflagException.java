package edu.redflag.error;

/**
 * Root checked exception for Project Redflag gateway faults.
 */
public abstract class RedflagException extends Exception {
    protected RedflagException(String message) {
        super(message);
    }

    protected RedflagException(String message, Throwable cause) {
        super(message, cause);
    }
}
