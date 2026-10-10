package edu.redflag.infer;

import edu.redflag.error.NativeBridgeException;
import ml.dmlc.xgboost4j.java.DMatrix;
import ml.dmlc.xgboost4j.java.XGBoostError;

/**
 * AutoCloseable RAII wrapper for native off-heap XGBoost DMatrix memory.
 * Guarantees direct C++ memory deallocation via dispose() upon scope exit.
 */
public final class NativeMatrix implements AutoCloseable {
    private final DMatrix dm;

    public NativeMatrix(float[] data, int rows, int cols) {
        try {
            this.dm = new DMatrix(data, rows, cols, Float.NaN);
        } catch (XGBoostError e) {
            throw new NativeBridgeException("Failed to allocate native DMatrix off-heap buffer", e);
        }
    }

    public DMatrix raw() {
        return dm;
    }

    @Override
    public void close() {
        if (dm != null) {
            dm.dispose();
        }
    }
}
