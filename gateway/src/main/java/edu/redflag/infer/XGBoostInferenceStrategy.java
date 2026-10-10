package edu.redflag.infer;

import edu.redflag.error.InferenceTimeoutException;
import edu.redflag.error.NativeBridgeException;

import java.util.Arrays;

/**
 * Primary ML inference strategy delegating to XGBoost4J via JNI.
 * Includes a synthetic warm-up protocol to trigger JIT C1/C2 compilation prior to live serial ingestion.
 */
public final class XGBoostInferenceStrategy implements InferenceStrategy, AutoCloseable {
    private final NativeModel model;
    private final int expectedFeatures;

    public XGBoostInferenceStrategy(NativeModel model, int expectedFeatures) {
        this.model = model;
        this.expectedFeatures = expectedFeatures;
    }

    @Override
    public float classify(float[] features) throws InferenceTimeoutException {
        if (features.length != expectedFeatures) {
            throw new NativeBridgeException("Feature array dimension mismatch: expected " + expectedFeatures + ", got " + features.length, null);
        }
        return model.predict(features);
    }

    @Override
    public String name() {
        return "xgboost4j";
    }

    /**
     * Executes synthetic inference cycles to promote hot methods to C2 compiled tier
     * and prime native JNI caches.
     *
     * @param cycles Number of warmup iterations (e.g., 5000)
     */
    public void warmUp(int cycles) {
        float[] benignSample = new float[expectedFeatures];
        float[] attackSample = new float[expectedFeatures];
        Arrays.fill(benignSample, 1000.0f);
        Arrays.fill(attackSample, 0.5f);

        float sink = 0.0f;
        for (int i = 0; i < cycles; i++) {
            sink += model.predict((i % 2 == 0) ? benignSample : attackSample);
        }
        if (Float.isInfinite(sink)) {
            System.out.println("Warmup sink initialized: " + sink);
        }
    }

    @Override
    public void close() {
        model.close();
    }
}
