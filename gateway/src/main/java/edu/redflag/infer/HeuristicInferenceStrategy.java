package edu.redflag.infer;

/**
 * Deterministic fallback strategy used when the native XGBoost Circuit Breaker is OPEN.
 * Uses domain knowledge of Mirai attack signatures:
 * Packet inter-arrival mean < 5.0 ms AND inter-arrival std < 1.0 ms -> flood with 0.95 confidence.
 */
public final class HeuristicInferenceStrategy implements InferenceStrategy {
    private final int idxMean20;
    private final int idxStd20;

    public HeuristicInferenceStrategy(int idxMean20, int idxStd20) {
        this.idxMean20 = idxMean20;
        this.idxStd20 = idxStd20;
    }

    @Override
    public float classify(float[] features) {
        float mean = (idxMean20 >= 0 && idxMean20 < features.length) ? features[idxMean20] : 1000.0f;
        float std  = (idxStd20 >= 0 && idxStd20 < features.length)   ? features[idxStd20]  : 20.0f;

        // Signature of volumetric line-rate flood: hyper-fast mean and near-zero dispersion
        if (mean < 5.0f && std < 1.0f) {
            return 0.95f;
        }
        return 0.05f;
    }

    @Override
    public String name() {
        return "heuristic";
    }
}
