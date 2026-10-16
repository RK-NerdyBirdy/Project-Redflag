package edu.redflag.core;

import edu.redflag.decode.PacketDecoderFactory;
import edu.redflag.error.InferenceTimeoutException;
import edu.redflag.error.NativeBridgeException;
import edu.redflag.error.PacketCorruptedException;
import edu.redflag.event.TelemetryObservable;
import edu.redflag.event.TelemetryObserver;
import edu.redflag.infer.InferenceStrategy;
import edu.redflag.model.AttackVector;
import edu.redflag.model.Packet;
import edu.redflag.model.RawPacket;
import edu.redflag.model.TelemetryFrame;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Concurrency engine routing ingested packets to striped single-thread lanes per node ID.
 * Coordinates feature generation, circuit breaker evaluation, ML inference, and observer publishing.
 * Supports multiple ingress sources (e.g. USB Serial and TCP/WiFi edge nodes).
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
    private final List<Thread> dispatcherThreads = new CopyOnWriteArrayList<>();

    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicBoolean mitigationEnabled = new AtomicBoolean(true);

    private final AtomicLong processedCount = new AtomicLong(0L);
    private final AtomicLong malformedCount = new AtomicLong(0L);
    private final AtomicLong attackCount = new AtomicLong(0L);

    private static volatile BlockingQueue<RawPacket> GLOBAL_INGRESS_QUEUE;
    private static volatile TelemetryObservable GLOBAL_EVENT_BUS;
    private static volatile GatewayPipeline GLOBAL_INSTANCE;
    private static final List<TelemetryObserver> GLOBAL_OBSERVERS = new CopyOnWriteArrayList<>();

    public static BlockingQueue<RawPacket> getGlobalIngressQueue() {
        return GLOBAL_INGRESS_QUEUE;
    }

    public static TelemetryObservable getGlobalEventBus() {
        return GLOBAL_EVENT_BUS;
    }

    public static GatewayPipeline getInstance() {
        return GLOBAL_INSTANCE;
    }

    /**
     * Subscribes an observer globally, registering it immediately if the event bus is active,
     * or caching it to be attached automatically when the pipeline event bus is initialized.
     *
     * @param observer telemetry observer to register
     */
    public static void subscribeGlobal(final TelemetryObserver observer) {
        if (observer == null) return;
        if (!GLOBAL_OBSERVERS.contains(observer)) {
            GLOBAL_OBSERVERS.add(observer);
        }
        final var bus = GLOBAL_EVENT_BUS;
        if (bus != null) {
            bus.subscribe(observer);
        }
    }

    /**
     * Constructs a GatewayPipeline.
     *
     * @param ingressQueue Primary ingress queue (e.g. for serial telemetry)
     * @param deviceRegistry Registry tracking node sessions and observers
     * @param schema Feature schema definition
     * @param primaryStrategy High-performance XGBoost inference strategy
     * @param fallbackStrategy Resilient heuristic inference strategy
     * @param eventBus Telemetry observable publishing detection results
     * @param laneCount Number of striped executor lanes
     */
    public GatewayPipeline(
        final BlockingQueue<RawPacket> ingressQueue,
        final DeviceRegistry deviceRegistry,
        final FeatureSchema schema,
        final InferenceStrategy primaryStrategy,
        final InferenceStrategy fallbackStrategy,
        final TelemetryObservable eventBus,
        final int laneCount
    ) {
        this.ingressQueue = ingressQueue;
        this.deviceRegistry = deviceRegistry;
        GLOBAL_INGRESS_QUEUE = ingressQueue;
        GLOBAL_EVENT_BUS = eventBus;
        GLOBAL_INSTANCE = this;
        for (final TelemetryObserver obs : GLOBAL_OBSERVERS) {
            eventBus.subscribe(obs);
        }
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
                    final Thread t = new Thread(r, "pipeline-lane-" + laneId);
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.DiscardOldestPolicy()
            );
        }

        final var primaryDispatcher = new Thread(() -> dispatchLoop(ingressQueue), "pipeline-dispatcher-primary");
        primaryDispatcher.setDaemon(true);
        dispatcherThreads.add(primaryDispatcher);
    }

    /**
     * Attaches an additional ingress queue (e.g., for TCP network ingestion) and immediately starts
     * a dedicated worker thread dispatching its frames into the striped pipeline lanes.
     *
     * @param queue additional blocking queue of raw frames
     */
    public void attachIngressQueue(final BlockingQueue<RawPacket> queue) {
        Objects.requireNonNull(queue, "Ingress queue must not be null");
        final var thread = new Thread(() -> dispatchLoop(queue), "pipeline-dispatcher-attached-" + queue.hashCode());
        thread.setDaemon(true);
        dispatcherThreads.add(thread);
        if (running.get()) {
            thread.start();
        }
    }

    public void start() {
        for (final var t : dispatcherThreads) {
            if (!t.isAlive()) {
                t.start();
            }
        }
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

    private void dispatchLoop(final BlockingQueue<RawPacket> queue) {
        while (running.get() || !queue.isEmpty()) {
            try {
                final RawPacket raw = queue.poll(200, TimeUnit.MILLISECONDS);
                if (raw == null) continue;

                final Packet packet;
                try {
                    packet = PacketDecoderFactory.forLine(raw.line()).decode(raw);
                } catch (final PacketCorruptedException e) {
                    malformedCount.incrementAndGet();
                    continue;
                }

                // Striped lane dispatch preserving per-node causal sequence ordering
                final int lane = Math.floorMod(packet.nodeId().hashCode(), lanes.length);
                lanes[lane].execute(() -> processPacket(packet, raw.rxNanos()));
            } catch (final InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void processPacket(final Packet packet, final long rxNanos) {
        final NodeSession session = deviceRegistry.getSession(packet.nodeId());
        final boolean ready = session.update(packet);

        if (!ready) {
            eventBus.publish(new TelemetryFrame(
                packet,
                session.lastDeltaTMs(),
                0.0,
                0.0f,
                false,
                AttackVector.NONE,
                0L,
                200,
                true
            ));
            processedCount.incrementAndGet();
            return;
        }

        final float[] features = featureBuffers.get();
        session.fill(features, schema, packet);

        final long t0 = System.nanoTime();
        float score;
        try {
            final InferenceStrategy strategy = circuitBreaker.allow() ? primaryStrategy : fallbackStrategy;
            try {
                score = strategy.classify(features);
                if (strategy == primaryStrategy) {
                    circuitBreaker.recordSuccess();
                }
            } catch (final NativeBridgeException | InferenceTimeoutException ex) {
                circuitBreaker.recordFailure();
                score = fallbackStrategy.classify(features);
            }
        } catch (final InferenceTimeoutException ex) {
            return;
        }
        final long inferDurationNanos = System.nanoTime() - t0;

        // High-sensitivity volumetric detection:
        // Decisively detect Mirai UDP line-rate floods even when serial CDC framing introduces micro-jitter.
        // Safe sensor traffic is TCP/CON with Δt ≈ 1000ms (score ~ 0.057).
        // Volumetric attack traffic is UDP/INT with Δt < 5.0ms and low dispersion.
        final double dt = session.lastDeltaTMs();
        final double std20 = session.lastStd20();
        final double std10 = session.lastStd10();
        final double mean10 = session.lastMean10();

        final boolean isUdpFloodSignature = "udp".equalsIgnoreCase(packet.proto()) && dt < 5.0 &&
            ((std20 < 1.0) || (std10 < 1.0 && mean10 < 5.0));

        if (isUdpFloodSignature) {
            // Decisively elevate confidence into high-severity attack regime [0.88 - 0.99]
            if (score >= 0.25f) {
                score = Math.max(score, 0.88f + (score - 0.25f) * 0.15f);
            } else {
                score = Math.max(score, 0.82f);
            }
        }

        final boolean isAttack = (score >= 0.45f);
        final int httpStatus = (isAttack && mitigationEnabled.get()) ? 403 : 200;

        if (isAttack) {
            attackCount.incrementAndGet();
        }
        processedCount.incrementAndGet();

        final TelemetryFrame frame = new TelemetryFrame(
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
        for (final var t : dispatcherThreads) {
            try {
                t.join(1000);
            } catch (final InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }

        for (final ThreadPoolExecutor lane : lanes) {
            lane.shutdown();
        }
        for (final ThreadPoolExecutor lane : lanes) {
            try {
                if (!lane.awaitTermination(2, TimeUnit.SECONDS)) {
                    lane.shutdownNow();
                }
            } catch (final InterruptedException e) {
                lane.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        if (primaryStrategy instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (final Exception ignored) {}
        }
    }
}
