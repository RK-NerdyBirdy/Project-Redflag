---
name: java-gateway
description: >-
  Use this skill when building or modifying the Java gateway application for Project Redflag.
  Covers the Maven project setup, package structure, serial ingestion, feature engineering,
  XGBoost4J JNI inference with circuit breaker, observer-based event dispatch, JDBC persistence,
  and the overall concurrency architecture.
---

# Java Gateway Skill — Project Redflag

## Technology Stack
- **JDK 21**, Maven build system
- **xgboost4j** — JNI bridge to native XGBoost (match training version)
- **jSerialComm** — cross-platform serial port access
- **Jackson** — JSON parsing (ObjectMapper)
- **HikariCP** — JDBC connection pool
- **JavaFX 21** — live dashboard (see `javafx-dashboard` skill)
- **Jakarta Servlet 6 / WebSocket 2.1** — web console (see `web-console` skill)

## Maven Setup

See [references/pom-template.md](./references/pom-template.md) for a complete pom.xml template.

Key dependencies:
```xml
<dependency>
  <groupId>ml.dmlc</groupId>
  <artifactId>xgboost4j_2.12</artifactId>
  <version>2.0.3</version>  <!-- match training version -->
</dependency>
<dependency>
  <groupId>com.fazecast</groupId>
  <artifactId>jSerialComm</artifactId>
  <version>2.10.4</version>
</dependency>
<dependency>
  <groupId>com.fasterxml.jackson.core</groupId>
  <artifactId>jackson-databind</artifactId>
  <version>2.17.0</version>
</dependency>
<dependency>
  <groupId>com.zaxxer</groupId>
  <artifactId>HikariCP</artifactId>
  <version>5.1.0</version>
</dependency>
```

## Package Structure

```
edu.redflag
 ├─ model        Packet, RawPacket, TelemetryFrame, AttackVector
 ├─ decode       PacketDecoder (interface), PacketDecoderFactory, JsonPacketDecoder, CsvPacketDecoder
 ├─ core         NodeSession, FeatureVectorBuilder, FeatureSchema, GatewayPipeline, CircuitBreaker
 ├─ infer        InferenceStrategy (interface), XGBoostInferenceStrategy, HeuristicInferenceStrategy, NativeModel
 ├─ io           SerialReaderRunnable, SerialLink
 ├─ event        TelemetryObservable, TelemetryObserver (interface), MitigationController, AlertAggregator
 ├─ persist      Db, AuditWriter
 ├─ web          TelemetryIngestServlet, MitigationReportServlet, TelemetryWebSocket
 ├─ fx           RedflagApp, DashboardController
 └─ error        RedflagException hierarchy
```

## Design Patterns (MUST implement all)

### 1. Strategy Pattern — `InferenceStrategy`
```java
public interface InferenceStrategy {
    float classify(float[] features) throws InferenceTimeoutException;
    String name();
}
```
Implementations: `XGBoostInferenceStrategy` (primary), `HeuristicInferenceStrategy` (fallback)

### 2. Factory Pattern — `PacketDecoderFactory`
Selects decoder based on first character of the line:
- `{` → `JsonPacketDecoder`
- digit → `CsvPacketDecoder`
- else → `FramingErrorException`

### 3. Observer Pattern — `TelemetryObservable`
Uses `CopyOnWriteArrayList` for subscribers. All subscribers receive every `TelemetryFrame`:
- `MitigationController` — sends BLOCK/SAFE to Arduino, HTTP verdict
- `AuditWriter` — batched JDBC persistence
- `DashboardController` — JavaFX chart updates
- `TelemetryWebSocket.Broadcaster` — WebSocket push to browsers

### 4. Circuit Breaker — JNI failure isolation
After N consecutive JNI failures → OPEN state → use `HeuristicInferenceStrategy`
After cooldown → HALF_OPEN → one probe with primary
On success → CLOSED → back to primary

## Concurrency Architecture

### Thread Topology
1. **serial-reader (1)** — blocking `readLine()`, pushes to queue
2. **dispatcher (1)** — decodes, routes by `nodeId.hashCode() % lanes`
3. **lane-0, lane-1 (2)** — single-thread executors, feature extraction + inference
4. **audit-writer (1)** — batched JDBC (200 rows or 250ms flush)
5. **JavaFX Application Thread** — chart updates via `Platform.runLater`
6. **Container threads** — Servlet/WebSocket

### Synchronization
- `ArrayBlockingQueue<RawPacket>` (cap 4096) — serial → pipeline
- `ReentrantLock` per `NodeSession` — short critical sections
- `AtomicLong` / `AtomicBoolean` — counters and flags (lock-free)
- `ThreadLocal<float[]>` — feature buffers per lane (no sharing)
- `ReentrantLock` around `Booster.predict` — single prediction at a time

## Feature Vector (51 elements)

The `FeatureSchema` class reads `feature_names.json` and resolves column indices once at startup.

| Group               | Count | Columns |
|---------------------|-------|---------|
| Raw per-packet      | 2     | `delta_t`, `bytes` |
| Rolling (W×5)       | 20    | `delta_t_mean_W`, `delta_t_std_W`, `delta_t_max_W`, `bytes_mean_W`, `pkt_count_W` |
| Protocol one-hot    | 8     | `proto_tcp`, `proto_udp`, ... `proto_other` |
| State one-hot       | 12    | `state_CON`, `state_INT`, ... `state_other` |
| Flags one-hot       | 9     | `flgs_e`, `flgs_e s`, ... `flgs_other` |

## JNI Safety Rules (CRITICAL)
1. Every `DMatrix` wrapped in `AutoCloseable` → `dispose()` in `close()`
2. `NativeModel` owns the `Booster`; `close()` is idempotent via `AtomicBoolean`
3. `NativeModel.load()` does fail-fast: `ModelNotLoadedException` on bad path or wrong arch
4. Run 5000-cycle warm-up before accepting live traffic
5. Set `booster.setParam("nthread", "1")` to avoid OpenMP oversubscription

## Startup Sequence
1. Load `FeatureSchema` from `models/feature_names.json`
2. Load `NativeModel` from `models/redflag_xgb.json` (fail-fast)
3. Warm up XGBoost (5000 cycles, alternate benign/flood vectors)
4. Initialize DB pool (`Db.init(...)`)
5. Create `AuditWriter`, `MitigationController`, `DashboardController`
6. Subscribe all observers to `TelemetryObservable`
7. Create and start `GatewayPipeline`
8. Open serial port → start `SerialReaderRunnable`
9. Register shutdown hook: `reader.close()` → `pipeline.close()` → `audit.close()` → `Db.close()`

## Exception Hierarchy
- `RedflagException` (checked, abstract base)
  - `PacketCorruptedException` → drop frame, continue
    - `FramingErrorException` → unrecognized format
  - `ModelNotLoadedException` → fatal, abort startup
  - `InferenceTimeoutException` → recoverable, try fallback
- `NativeBridgeException` (unchecked) → trips circuit breaker

## Session Management
- `RESET_GAP_US = 5,000,000` (5s) — any gap > 5s clears all windows
- 32-bit timestamp wrap: detect `t_i < t_{i-1}` with `delta > 2^31` → add `2^32` epoch offset
- Out-of-order `seq`: drop, increment counter
- First 10 packets per session: `BUFFERING` (no inference)
