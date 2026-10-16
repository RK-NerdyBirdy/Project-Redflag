package edu.redflag.core;

import edu.redflag.model.Packet;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Locale;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Encapsulates bounded temporal state and rolling statistics for a single edge IoT node.
 * Uses an object pool (recycled Sample instances) to ensure zero GC allocation in steady state.
 */
public final class NodeSession {
    public static final int[] WINDOWS = {10, 20, 30, 50};
    public static final int CAPACITY = 50;
    public static final int MIN_PACKETS = 10;
    private static final long RESET_GAP_US = 5_000_000L; // 5.0s session gap threshold
    private static final long WRAP_THRESHOLD = 1L << 32;

    /** Mutable sample instance recycled between evictions to avoid heap churn. */
    private static final class Sample {
        double dtMs;
        int bytes;
    }

    private final ReentrantLock lock = new ReentrantLock();
    private final ArrayDeque<Sample> window = new ArrayDeque<>(CAPACITY + 1);
    private final double[] dtScratch = new double[CAPACITY];
    private final double[] bytesScratch = new double[CAPACITY];

    private long lastRawUs = -1;
    private long lastUnwrappedUs = -1;
    private long epochOffsetUs = 0;
    private long lastSeq = -1;
    private long packetsInSession = 0;
    private Sample spare = null;

    private double lastDeltaTMs = Double.NaN;
    private double lastStd20 = Double.NaN;
    private double lastStd10 = Double.NaN;
    private double lastMean10 = Double.NaN;

    public boolean isReady() {
        return packetsInSession >= MIN_PACKETS;
    }

    /**
     * Updates session with a new packet arrival.
     *
     * @param p Ingested packet
     * @return True if session has enough history (>= 10 packets) for ML inference
     */
    public boolean update(Packet p) {
        lock.lock();
        try {
            // Drop out-of-order or duplicate sequence numbers
            if (p.seq() <= lastSeq && lastSeq >= 0 && p.seq() != 0) {
                return isReady();
            }
            lastSeq = p.seq();

            long raw = p.stimeUs();
            // Handle 32-bit hardware timer overflow
            if (lastRawUs >= 0 && raw < lastRawUs && (lastRawUs - raw) > (WRAP_THRESHOLD >> 1)) {
                epochOffsetUs += WRAP_THRESHOLD;
            }
            long t = raw + epochOffsetUs;

            // Session flush on gap > 5 seconds
            if (lastUnwrappedUs >= 0 && (t - lastUnwrappedUs) > RESET_GAP_US) {
                recycleAll();
                packetsInSession = 0;
                lastDeltaTMs = Double.NaN;
                lastStd20 = Double.NaN;
                lastUnwrappedUs = -1;
            }

            packetsInSession++;
            if (lastUnwrappedUs >= 0) {
                Sample s = obtain();
                s.dtMs = (t - lastUnwrappedUs) / 1000.0; // microseconds to milliseconds
                s.bytes = p.bytes();

                if (window.size() == CAPACITY) {
                    spare = window.pollFirst();
                }
                window.addLast(s);
                lastDeltaTMs = s.dtMs;
            } else {
                lastDeltaTMs = 0.0;
            }

            lastRawUs = raw;
            lastUnwrappedUs = t;
            return isReady();
        } finally {
            lock.unlock();
        }
    }

    private Sample obtain() {
        Sample s = (spare != null) ? spare : (window.size() < CAPACITY ? new Sample() : window.pollFirst());
        spare = null;
        return (s != null) ? s : new Sample();
    }

    private void recycleAll() {
        window.clear();
        spare = null;
    }

    public double lastDeltaTMs() {
        lock.lock();
        try {
            return lastDeltaTMs;
        } finally {
            lock.unlock();
        }
    }

    public double lastStd20() {
        lock.lock();
        try {
            return lastStd20;
        } finally {
            lock.unlock();
        }
    }

    public double lastStd10() {
        lock.lock();
        try {
            return lastStd10;
        } finally {
            lock.unlock();
        }
    }

    public double lastMean10() {
        lock.lock();
        try {
            return lastMean10;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Populates the pre-allocated feature array according to the loaded FeatureSchema.
     *
     * @param x      Float vector of length schema.size
     * @param schema Loaded manifest schema
     * @param p      Current packet
     */
    public void fill(float[] x, FeatureSchema schema, Packet p) {
        lock.lock();
        try {
            int m = 0;
            for (Sample s : window) {
                dtScratch[m] = s.dtMs;
                bytesScratch[m] = s.bytes;
                m++;
            }

            Arrays.fill(x, 0.0f);

            // Raw per-packet features
            if (schema.idxDt >= 0) x[schema.idxDt] = (float) (Double.isNaN(lastDeltaTMs) ? 0.0 : lastDeltaTMs);
            if (schema.idxBytes >= 0) x[schema.idxBytes] = p.bytes();
            if (schema.idxPkts >= 0) x[schema.idxPkts] = 1.0f;

            // Rolling statistics over W in {10, 20, 30, 50}
            for (int w = 0; w < WINDOWS.length; w++) {
                int winSize = WINDOWS[w];
                int n = Math.min(winSize, m);
                int from = m - n;

                double dtSum = 0;
                double byteSum = 0;
                double maxDt = Double.NEGATIVE_INFINITY;

                for (int i = from; i < m; i++) {
                    dtSum += dtScratch[i];
                    byteSum += bytesScratch[i];
                    if (dtScratch[i] > maxDt) maxDt = dtScratch[i];
                }

                double meanDt = (n > 0) ? (dtSum / n) : 0.0;
                double meanBytes = (n > 0) ? (byteSum / n) : 0.0;

                double varDt = 0;
                double varBytes = 0;
                if (n > 1) {
                    for (int i = from; i < m; i++) {
                        double dDt = dtScratch[i] - meanDt;
                        varDt += dDt * dDt;
                        double dBy = bytesScratch[i] - meanBytes;
                        varBytes += dBy * dBy;
                    }
                    varDt /= (n - 1);
                    varBytes /= (n - 1);
                }

                double stdDt = Math.sqrt(varDt);
                double stdBytes = Math.sqrt(varBytes);

                if (schema.idxMeanDt[w] >= 0) x[schema.idxMeanDt[w]] = (float) meanDt;
                if (schema.idxStdDt[w] >= 0)  x[schema.idxStdDt[w]]  = (float) stdDt;
                if (schema.idxMaxDt[w] >= 0)  x[schema.idxMaxDt[w]]  = (float) (n > 0 ? maxDt : 0.0);
                if (schema.idxMeanBytes[w] >= 0) x[schema.idxMeanBytes[w]] = (float) meanBytes;
                if (schema.idxStdBytes[w] >= 0)  x[schema.idxStdBytes[w]]  = (float) stdBytes;
                if (schema.idxPktCount[w] >= 0)  x[schema.idxPktCount[w]]  = (float) n;

                if (winSize == 10) {
                    lastStd10 = stdDt;
                    lastMean10 = meanDt;
                } else if (winSize == 20) {
                    lastStd20 = stdDt;
                }
            }

            // Categorical one-hot mapping
            applyOneHot(x, schema.protoIndices, p.proto().toLowerCase(Locale.ROOT), schema.protoOther);
            applyOneHot(x, schema.stateIndices, p.state(), schema.stateOther);
            applyOneHot(x, schema.flagIndices,  p.flgs(),  schema.flagOther);

            // Numeric encoding proxies if present in model
            if (schema.idxProtoNum >= 0) {
                x[schema.idxProtoNum] = "udp".equalsIgnoreCase(p.proto()) ? 2.0f : 1.0f;
            }
            if (schema.idxStateNum >= 0) {
                x[schema.idxStateNum] = "INT".equalsIgnoreCase(p.state()) ? 2.0f : 1.0f;
            }
            if (schema.idxFlgsNum >= 0) {
                x[schema.idxFlgsNum] = 1.0f;
            }
        } finally {
            lock.unlock();
        }
    }

    private static void applyOneHot(float[] x, java.util.Map<String, Integer> map, String key, int otherIdx) {
        Integer idx = map.get(key);
        if (idx != null && idx >= 0 && idx < x.length) {
            x[idx] = 1.0f;
        } else if (otherIdx >= 0 && otherIdx < x.length) {
            x[otherIdx] = 1.0f;
        }
    }
}
