package edu.redflag.infer;

import edu.redflag.error.InferenceTimeoutException;

/**
 * Strategy pattern interface for IoT flood packet classification.
 */
public interface InferenceStrategy {
    /**
     * Evaluates the feature vector and returns attack probability in [0.0, 1.0].
     *
     * @param features Pre-computed 51-element (or schema-sized) float feature array
     * @return Classification score (>= 0.5 indicates attack)
     * @throws InferenceTimeoutException if inference deadline is breached
     */
    float classify(float[] features) throws InferenceTimeoutException;

    /**
     * Identifying name of strategy (e.g., "xgboost4j", "heuristic").
     */
    String name();
}
