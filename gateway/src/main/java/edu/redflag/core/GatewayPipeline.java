package edu.redflag.core;

import edu.redflag.decode.PacketDecoderFactory;
import edu.redflag.error.InferenceTimeoutException;
import edu.redflag.error.NativeBridgeException;
import edu.redflag.error.PacketCorruptedException;
import edu.redflag.event.TelemetryObservable;
import edu.redflag.core.DeviceRegistry;
import edu.redflag.infer.InferenceStrategy;
import edu.redflag.model.AttackVector;
import edu.redflag.model.Packet;
import edu.redflag.model.RawPacket;
import edu.redflag.model.TelemetryFrame;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Concurrency engine routing ingested packets to striped single-thread lanes per node ID.
 * Coordinates feature generation, circuit breaker evaluation, ML inference, and observer publishing.
 */
public final class GatewayPipeline implements AutoCloseable {
    private final BlockingQueue<RawPacket> ingressQueue;
    private final DeviceRegistry deviceRegistry;
    private final ThreadPoolExecutor[] lanes;
    private final ThreadLocal<float[]> featureBuffers;
    private final FeatureSchema schema;
    private final InferenceStrategy primaryStrategy;
    private final InferenceStrategy fallbackStrategy;
    private final CircuitBreaker circuitBreaker = new CircuitBreaker(5, 10_000L);
    private final TelemetryObservable eventBus;
    private final Thread dispatcherThread;

    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicBoolean mitigationEnabled = new AtomicBoolean(true);

    private final AtomicLong processedCount = new AtomicLong(0L);
    private final AtomicLong malformedCount = new AtomicLong(0L);
    private final AtomicLong attackCount = new AtomicLong(0L);

    private static volatile BlockingQueue<RawPacket> GLOBAL_INGRESS_QUEUE;
    private static volatile TelemetryObservable GLOBAL_EVENT_BUS;
    private static volatile GatewayPipeline GLOBAL_INSTANCE;

    public static BlockingQueue<RawPacket> getGlobalIngressQueue() {
        return GLOBAL_INGRESS_QUEUE;
    }
    
    public static TelemetryObservable getGlobalEventBus() {
        return GLOBAL_EVENT_BUS;
    }
    
    public static GatewayPipeline getInstance() {
        return GLOBAL_INSTANCE;
    }

    public GatewayPipeline(
        BlockingQueue<RawPacket> ingressQueue,
        DeviceRegistry deviceRegistry,
        FeatureSchema schema,
        InferenceStrategy primaryStrategy,
        InferenceStrategy fallbackStrategy,
        TelemetryObservable eventBus,
        int laneCount
    ) {
        this.ingressQueue = ingressQueue;
        this.deviceRegistry = deviceRegistry;
        GLOBAL_INGRESS_QUEUE = ingressQueue;
        GLOBAL_EVENT_BUS = eventBus;
        GLOBAL_INSTANCE = this;
        this.schema = schema;
        this.primaryStrategy = primaryStrategy;
        this.fallbackStrategy = fallbackStrategy;
        this.eventBus = eventBus;
        this.featureBuffers = ThreadLocal.withInitial(() -> new float[schema.size]);

        this.lanes = new ThreadPoolExecutor[laneCount];
        for (int i = 0; i < laneCount; i++) {
            final int laneId = i;
            lanes[i] = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(2048),
                r -> {
                    Thread t = new Thread(r, "pipeline-lane-" + laneId);
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.DiscardOldestPolicy()
            );
        }

        this.dispatcherThread = new Thread(this::dispatchLoop, "pipeline-dispatcher");
        this.dispatcherThread.setDaemon(true);
    }

    public void start() {
        dispatcherThread.start();
    }

    public AtomicBoolean mitigationFlag() {
        return mitigationEnabled;
    }

    public long getProcessedCount() {
        return processedCount.get();
    }

    public long getMalformedCount() {
        return malformedCount.get();
    }

    public long getAttackCount() {
        return attackCount.get();
    }

    private void dispatchLoop() {
        while (running.get() || !ingressQueue.isEmpty()) {
            try {
                RawPacket raw = ingressQueue.poll(200, TimeUnit.MILLISECONDS);
                if (raw == null) continue;

                Packet packet;
                try {
                    packet = PacketDecoderFactory.forLine(raw.line()).decode(raw);
                } catch (PacketCorruptedException e) {
                    malformedCount.incrementAndGet();
                    continue;
                }

                // Striped lane dispatch preserving per-node causal sequence ordering
                int lane = Math.floorMod(packet.nodeId().hashCode(), lanes.length);
                lanes[lane].execute(() -> processPacket(packet, raw.rxNanos()));
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void processPacket(Packet packet, long rxNanos) {
        NodeSession session = deviceRegistry.getSession(packet.nodeId());
        boolean ready = session.update(packet);

        if (!ready) {
            eventBus.publish(TelemetryFrame.buffering(packet));
            processedCount.incrementAndGet();
            return;
        }

        float[] features = featureBuffers.get();
        session.fill(features, schema, packet);

        long t0 = System.nanoTime();
        float score;
        try {
            InferenceStrategy strategy = circuitBreaker.allow() ? primaryStrategy : fallbackStrategy;
            try {
                score = strategy.classify(features);
                if (strategy == primaryStrategy) {
                    circuitBreaker.recordSuccess();
                }
            } catch (NativeBridgeException | InferenceTimeoutException ex) {
                circuitBreaker.recordFailure();
                score = fallbackStrategy.classify(features);
            }
        } catch (InferenceTimeoutException ex) {
            return;
        }
        long inferDurationNanos = System.nanoTime() - t0;

        boolean isAttack = (score >= 0.5f);
        int httpStatus = (isAttack && mitigationEnabled.get()) ? 403 : 200;

        if (isAttack) {
            attackCount.incrementAndGet();
        }
        processedCount.incrementAndGet();

        TelemetryFrame frame = new TelemetryFrame(
            packet,
            session.lastDeltaTMs(),
            session.lastStd20(),
            score,
            isAttack,
            isAttack ? AttackVector.UDP_FLOOD : AttackVector.NONE,
            inferDurationNanos,
            httpStatus,
            false
        );
        eventBus.publish(frame);
    }

    @Override
    public void close() {
        running.set(false);
        try {
            dispatcherThread.join(1000);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }

        for (ThreadPoolExecutor lane : lanes) {
            lane.shutdown();
        }
        for (ThreadPoolExecutor lane : lanes) {
            try {
                if (!lane.awaitTermination(2, TimeUnit.SECONDS)) {
                    lane.shutdownNow();
                }
            } catch (InterruptedException e) {
                lane.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        if (primaryStrategy instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception ignored) {}
        }
    }
}
