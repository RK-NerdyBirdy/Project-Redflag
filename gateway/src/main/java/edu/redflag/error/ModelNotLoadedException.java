package edu.redflag.error;

/**
 * Checked exception indicating an unrecoverable failure loading the XGBoost / ML model artifact.
 * Halts gateway startup (fail-fast principle).
 */
public class ModelNotLoadedException extends RedflagException {
    public ModelNotLoadedException(String message) {
        super(message);
    }

    public ModelNotLoadedException(String message, Throwable cause) {
        super(message, cause);
    }
}
