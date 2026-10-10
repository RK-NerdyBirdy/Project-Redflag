package edu.redflag.infer;

import edu.redflag.error.ModelNotLoadedException;
import edu.redflag.error.NativeBridgeException;
import ml.dmlc.xgboost4j.java.Booster;
import ml.dmlc.xgboost4j.java.XGBoost;
import ml.dmlc.xgboost4j.java.XGBoostError;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Manages the native C++ Booster handle with strict lifecycle management.
 * Idempotent close() ensures clean disposal without double-free errors.
 */
public final class NativeModel implements AutoCloseable {
    private final Booster booster;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final ReentrantLock predictLock = new ReentrantLock();

    private NativeModel(Booster booster) {
        this.booster = booster;
    }

    /**
     * Loads the native model from disk with fail-fast validation.
     *
     * @param modelFile Path to redflag_xgb.json
     * @return Initialized NativeModel instance
     * @throws ModelNotLoadedException on missing file, JNI linkage error, or wrong architecture
     */
    public static NativeModel load(Path modelFile) throws ModelNotLoadedException {
        if (!Files.isReadable(modelFile)) {
            throw new ModelNotLoadedException("Model file unreadable or not found: " + modelFile.toAbsolutePath(), null);
        }
        try {
            Booster b = XGBoost.loadModel(modelFile.toString());
            // Cap OpenMP worker threads to 1 to avoid thread oversubscription on edge CPUs
            b.setParam("nthread", 1);
            return new NativeModel(b);
        } catch (XGBoostError | UnsatisfiedLinkError e) {
            throw new ModelNotLoadedException("Failed to load native XGBoost model from " + modelFile + ": " + e.getMessage(), e);
        }
    }

    /**
     * Evaluates a single-row feature vector.
     */
    public float predict(float[] row) {
        if (closed.get()) {
            throw new NativeBridgeException("Booster native handle has already been disposed", null);
        }
        predictLock.lock();
        try (NativeMatrix m = new NativeMatrix(row, 1, row.length)) {
            float[][] out = booster.predict(m.raw());
            return out[0][0];
        } catch (XGBoostError e) {
            throw new NativeBridgeException("XGBoost native predict error: " + e.getMessage(), e);
        } finally {
            predictLock.unlock();
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            predictLock.lock();
            try {
                booster.dispose();
            } finally {
                predictLock.unlock();
            }
        }
    }
}
